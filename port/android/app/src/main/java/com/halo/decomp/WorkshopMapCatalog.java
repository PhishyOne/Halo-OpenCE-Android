package com.halo.decomp;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Small catalog of local multiplayer files. Never downloads previews or
 * treats a campaign/resource cache as a multiplayer map. Native loading
 * remains the final compatibility check. */
final class WorkshopMapCatalog {
    static final int MAX_CUSTOM=128;
    static final class MapItem {
        final String id,name; final File preview;
        MapItem(String id,String name,File preview){this.id=id;this.name=name;this.preview=preview;}
    }
    static List<MapItem> installed(File root){
        ArrayList<MapItem> out=new ArrayList<>();
        if(root==null)return out;
        for(LauncherMatch.MapChoice map:LauncherMatch.installedMaps(root)){
            if(map.id.isEmpty())continue;
            File file=new File(root,"maps/"+map.id+".map");
            if(Files.isSymbolicLink(file.toPath()))continue;
            out.add(new MapItem(map.id,map.label,preview(new File(root,"maps"),map.id)));
        }
        File folder=new File(root,"custom_maps");
        if(!folder.isDirectory()||Files.isSymbolicLink(folder.toPath()))return out;
        File[] candidates=folder.listFiles(f->CustomMapImporter.isValidName(f.getName()));
        if(candidates==null)return out;
        Arrays.sort(candidates,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        int added=0;
        for(File map:candidates){
            if(added>=MAX_CUSTOM)break;
            String name=map.getName(),id=name.substring(0,name.length()-4);
            try {
                String key=MatchWorkshop.checkedMap("ce:"+id);
                CustomMapImporter.validate(map,name);
                try(RandomAccessFile in=new RandomAccessFile(map,"r")){
                    in.seek(0x60); if(in.readUnsignedByte()!=1||in.readUnsignedByte()!=0)continue;
                }
                out.add(new MapItem(key,id,preview(folder,id)));added++;
            }catch(IOException|IllegalArgumentException ignored){}
        }
        return out;
    }
    private static File preview(File dir,String id){
        File file=new File(dir,id+".bmp");
        try{CustomMapImporter.validateImport(file,file.getName());return file;}
        catch(IOException|RuntimeException unavailable){return null;}
    }
    static String label(List<MapItem> maps,String id){
        for(MapItem m:maps)if(m.id.equals(id))return m.name;
        return (id.startsWith("ce:")?id.substring(3):id)+" (missing)";
    }
}
