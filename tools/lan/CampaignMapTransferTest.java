package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.DeflaterOutputStream;

/** Pure JDK: stock Xbox campaign auditing and private Android-document transfer. */
public final class CampaignMapTransferTest {
    private static int tests;
    private static void check(boolean good, String reason) {
        tests++;
        if (!good) throw new AssertionError(reason);
    }

    private static void put32(byte[] b,int at,long value) {
        for(int i=0;i<4;i++) b[at+i]=(byte)(value>>(i*8));
    }

    private static byte[] xboxMap(String name) throws IOException {
        byte[] header=new byte[2048],body=new byte[8192];
        byte[] ascii=name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy("daeh".getBytes(StandardCharsets.US_ASCII),0,header,0,4);
        put32(header,4,5);
        put32(header,8,header.length+body.length);
        put32(header,16,header.length+256);
        put32(header,20,128);
        System.arraycopy(ascii,0,header,32,ascii.length);
        System.arraycopy("toof".getBytes(StandardCharsets.US_ASCII),0,header,2044,4);
        put32(body,256+12,17);
        System.arraycopy("sgat".getBytes(StandardCharsets.US_ASCII),
            0,body,256+32,4);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(header);
        try(DeflaterOutputStream zip=new DeflaterOutputStream(out)) {
            zip.write(body);
        }
        return out.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p->{
                try { Files.deleteIfExists(p); }
                catch(IOException error){throw new RuntimeException(error);}
            });
        }
    }

    private static void failure(RunnableIo action,String phrase) {
        tests++;
        try {action.run();}
        catch(IOException expected) {
            if (!expected.getMessage().toLowerCase().contains(phrase.toLowerCase()))
                throw new AssertionError("Wrong rejection: "+expected.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection: "+phrase);
    }

    interface RunnableIo {void run() throws IOException;}

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("halo-stock-map-transfer-");
        Path friend=Files.createTempDirectory("halo-stock-map-friend-");
        try {
            Path maps=Files.createDirectory(root.resolve("maps"));
            Path friendsMaps=Files.createDirectory(friend.resolve("maps"));
            String[] ids=SidewinderRepair.CAMPAIGN_MAP_FILES;
            check(ids.length==10,"Expected all ten stock campaign levels");
            check(ids[3].equals("b30.map"),"Silent Cartographer position changed");
            check(ids[4].equals("b40.map")&&ids[5].equals("c10.map"),
                "Known damaged stock levels are not handled");
            for(String file:ids) {
                String name=file.substring(0,file.length()-4);
                byte[] good=xboxMap(name);
                Files.write(maps.resolve(file),good);
                check(SidewinderRepair.validate(maps.resolve(file).toFile(),file)==10240,
                    "Original campaign level refused: "+file);
            }
            // Single verified Xbox map selector now includes Sidewinder.
            // This remains distinct from v609 Custom Edition maps.
            byte[] sidewinder = xboxMap("sidewinder");
            Files.write(maps.resolve("sidewinder.map"),sidewinder);
            check(SidewinderRepair.validate(
                maps.resolve("sidewinder.map").toFile(),"sidewinder.map")==10240,
                "Xbox Sidewinder failed verified validator");
            ByteArrayOutputStream sideOut = new ByteArrayOutputStream();
            CampaignMapTransfer.Export sideExport=CampaignMapTransfer.export(
                root.toFile(),"sidewinder.map",sideOut);
            check(sideExport.filename.equals("sidewinder.map") &&
                Arrays.equals(sidewinder,sideOut.toByteArray()),
                "Consolidated map export cannot transfer Sidewinder");
            SidewinderRepair.Result sideImport=SidewinderRepair.install(
                new ByteArrayInputStream(sideOut.toByteArray()),
                friend.toFile(),"sidewinder.map");
            check(!sideImport.backedUpOriginal &&
                Arrays.equals(sidewinder,
                    Files.readAllBytes(friendsMaps.resolve("sidewinder.map"))),
                "Consolidated map import failed Sidewinder");
            String report=CampaignMapTransfer.audit(root.toFile(),null);
            check(report.contains("10/10 passed, 0 missing, 0 invalid"),
                "Full verified campaign audit failed: "+report);
            for(String file:ids)
                check(report.contains(file+" ("),"Missing from audit: "+file);

            // Admin-only library streams every verified mission, not an ISO
            // or a raw dump of the app's inaccessible private directory.
            ByteArrayOutputStream fullZip = new ByteArrayOutputStream();
            CampaignAdminArchive.Report fullLibrary =
                CampaignAdminArchive.export(root.toFile(), fullZip, null);
            check(fullLibrary.valid==10 && fullLibrary.invalid==0 &&
                fullLibrary.missing==0,"Complete admin campaign archive incorrect");
            Map<String,byte[]> fullEntries = zipContents(fullZip.toByteArray());
            check(fullEntries.size()==11,"Archive must contain 10 maps + inventory");
            check(!fullEntries.containsKey("maps/sidewinder.map"),
                "Sidewinder must not masquerade as a campaign mission");
            for(String id:ids)
                check(Arrays.equals(fullEntries.get("maps/"+id),
                    Files.readAllBytes(maps.resolve(id))),
                    "Admin archive changed source bytes: "+id);
            String fullManifest = new String(fullEntries.get(
                "CAMPAIGN-MAP-INVENTORY.txt"), StandardCharsets.UTF_8);
            check(fullManifest.contains("SUMMARY|valid=10|missing=0|invalid=0"),
                "Admin manifest lacks complete map audit");

            // Destination is only a caller-provided SAF stream.
            byte[] original=Files.readAllBytes(maps.resolve("b30.map"));
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            CampaignMapTransfer.Export exported=CampaignMapTransfer.export(
                root.toFile(),"b30.map",output);
            check(exported.filename.equals("b30.map"),"Wrong filename exported");
            check(exported.bytes==original.length,"Wrong export byte count");
            check(Arrays.equals(original,output.toByteArray()),"Map export changed bytes");
            check(exported.sha256.equals(hex(MessageDigest.getInstance("SHA-256")
                .digest(original))),"Wrong export checksum");

            // Friend receives a verified, named asset, never a user-created
            // filesystem path. The existing map is backed up before swap.
            byte[] previous="older damaged b30".getBytes(StandardCharsets.US_ASCII);
            Files.write(friendsMaps.resolve("b30.map"),previous);
            SidewinderRepair.Result imported=SidewinderRepair.install(
                new ByteArrayInputStream(output.toByteArray()),friend.toFile(),"b30.map");
            check(imported.backedUpOriginal,"Friend's previous map not saved");
            check(Arrays.equals(previous,Files.readAllBytes(
                friendsMaps.resolve("b30.map.previous"))),"Old friend map was changed");
            check(Arrays.equals(original,Files.readAllBytes(
                friendsMaps.resolve("b30.map"))),"Friend map import was not byte-identical");

            // The revised allowlist supports b40 and c10 too.
            for(String file:new String[]{"b40.map","c10.map"}) {
                SidewinderRepair.install(new ByteArrayInputStream(
                    Files.readAllBytes(maps.resolve(file))),friend.toFile(),file);
                check(Arrays.equals(Files.readAllBytes(maps.resolve(file)),
                    Files.readAllBytes(friendsMaps.resolve(file))),
                    "Additional repaired campaign map didn't install");
            }

            // Reject arbitrary paths, renamed assets and corrupt data.
            failure(()->CampaignMapTransfer.export(root.toFile(),
                "../save/u",new ByteArrayOutputStream()),"only the ten");
            failure(()->SidewinderRepair.install(
                new ByteArrayInputStream(xboxMap("a10")),friend.toFile(),
                "../a10.map"),"cannot be repaired");
            failure(()->SidewinderRepair.install(
                new ByteArrayInputStream(xboxMap("a30")),friend.toFile(),
                "d20.map"),"not Keyes");
            byte[] invalid=xboxMap("c20");
            invalid[0]=0;
            Files.write(maps.resolve("c20.map"),invalid);
            ByteArrayOutputStream rejected=new ByteArrayOutputStream();
            failure(()->CampaignMapTransfer.export(root.toFile(),
                "c20.map",rejected),"not a supported");
            check(rejected.size()==0,"Invalid map written to export target");

            Files.delete(maps.resolve("b40.map"));
            report=CampaignMapTransfer.audit(root.toFile(),null);
            check(report.contains("8/10 passed, 1 missing, 1 invalid"),
                "Audit missed invalid/missing maps: "+report);
            check(report.contains("b40.map") && report.contains("c20.map"),
                "Audit didn't identify broken mission IDs");
            ByteArrayOutputStream incompleteZip = new ByteArrayOutputStream();
            CampaignAdminArchive.Report partial =
                CampaignAdminArchive.export(root.toFile(), incompleteZip, null);
            check(partial.valid==8 && partial.missing==1 && partial.invalid==1,
                "Admin library must report invalid/missing missions, not copy them");
            Map<String,byte[]> incompleteEntries = zipContents(
                incompleteZip.toByteArray());
            check(incompleteEntries.size()==9,
                "Partial archive must contain only eight verified maps + inventory");
            check(!incompleteEntries.containsKey("maps/b40.map") &&
                  !incompleteEntries.containsKey("maps/c20.map"),
                "Admin library included missing or invalid campaign files");
            String partialManifest = new String(incompleteEntries.get(
                "CAMPAIGN-MAP-INVENTORY.txt"), StandardCharsets.UTF_8);
            check(partialManifest.contains("MISSING|b40.map") &&
                  partialManifest.contains("INVALID|c20.map") &&
                  partialManifest.contains("SUMMARY|valid=8|missing=1|invalid=1"),
                "Admin archive inventory did not identify unsafe copies");

            // Critical: exporting/importing maps never alters saved progress.
            Path saves=Files.createDirectories(friend.resolve("save/u"));
            Files.write(saves.resolve("profile"),new byte[]{12,25,37});
            SidewinderRepair.restorePrevious(friend.toFile(),"b30.map");
            check(Arrays.equals(previous,Files.readAllBytes(
                friendsMaps.resolve("b30.map"))),"Rollback failed");
            check(Arrays.equals(new byte[]{12,25,37},
                Files.readAllBytes(saves.resolve("profile"))),"Map operations modified saves");

            System.out.println("CampaignMapTransferTest PASS ("+tests+" checks)");
        } finally {
            deleteTree(root);
            deleteTree(friend);
        }
    }

    private static Map<String,byte[]> zipContents(byte[] archive) throws IOException {
        Map<String,byte[]> contents=new HashMap<>();
        try(ZipInputStream input=new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                check(!entry.isDirectory(),"Unexpected directory inside repair ZIP");
                check(entry.getName().equals("CAMPAIGN-MAP-INVENTORY.txt") ||
                    entry.getName().matches("maps/[a-d][0-9]{2}\\.map"),
                    "Unexpected archive entry: "+entry.getName());
                byte[] data=input.readAllBytes();
                check(contents.put(entry.getName(),data)==null,
                    "Duplicate ZIP entry: "+entry.getName());
                input.closeEntry();
            }
        }
        return contents;
    }

    private static String hex(byte[] digest) {
        StringBuilder result=new StringBuilder(64);
        for(byte b:digest)
            result.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return result.toString();
    }
}
