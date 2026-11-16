/* Compile the exact production validate_tag_table function against a
 * bounded byte-claim fixture. No map assets or production saves needed. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>

typedef unsigned char byte;
typedef int boolean;
#define TRUE 1
#define FALSE 0
#define NONE (-1L)
#define MAXIMUM_MESSAGE_LENGTH 512
#define STRUCTURE_BSP_GROUP_TAG 'sbsp'
enum { _pass_values, _pass_checks };
struct tag_schema_definition { unsigned long size; };
struct tag_schema_group {
    unsigned long group_tag, parent_group_tags[2];
    struct tag_schema_definition const *definition;
};
struct tag_validate_instance {
    unsigned long group_tag, parent_group_tags[2];
    long tag_index;
    char *name;
    void *base_address;
    unsigned long unused[2];
};
struct tag_validation {
    byte *region;
    unsigned long region_size;
    long tag_index, depth, corrections;
    char const *field_name;
    boolean refused;
};
static struct { int custom_edition; long corrections; char map_name[64]; } tag_validate_globals;
static char const tag_validate_empty_name[] = "";
static struct tag_schema_definition definition = {64};
static struct tag_schema_group groups[] = {
    {'vehi', {NONE,NONE}, &definition},
    {'weap', {NONE,NONE}, &definition},
    {'bitm', {NONE,NONE}, &definition}
};
static union { long alignment; byte data[8192]; } region;
static byte claims[8192];
static struct tag_validate_instance tags[1024];
static int values_seen[1024], checks_seen[1024];
static int allocations, deny_allocation, shared_child;
static struct tag_validation validation;

static void *audit_malloc(size_t size) {
    void *p;
    if (deny_allocation) return NULL;
    p = malloc(size);
    if (p) allocations++;
    return p;
}
static void audit_free(void *p) {
    if (p) { allocations--; free(p); }
}
static struct tag_schema_group const *schema_group_get(unsigned long group) {
    int i;
    for(i=0;i<3;i++) if(groups[i].group_tag==group) return &groups[i];
    return NULL;
}
static boolean string_valid(struct tag_validation *v, char const *name) {
    (void)v;
    return name != NULL;
}
static char const *tag_to_text(unsigned long group, char *text) {
    int i;
    for(i=0;i<4;i++) text[i]=(char)(group>>(24-i*8));
    text[4]=0;
    return text;
}
static void tag_validate_report(char const *message) { (void)message; }
static void tag_validate_refuse(struct tag_validation *v, char const *text,...) {
    (void)text;
    v->refused=TRUE;
}
static void tag_validate_correct(struct tag_validation *v, char const *text,...) {
    (void)text;
    v->corrections++;
}
static boolean region_contains(struct tag_validation *v, void const *p, unsigned long size) {
    uintptr_t start=(uintptr_t)v->region, address=(uintptr_t)p;
    return address>=start && address-start<=v->region_size &&
        size<=v->region_size-(address-start);
}
static boolean claim(void const *p, unsigned long size) {
    uintptr_t start=(uintptr_t)region.data, at=(uintptr_t)p;
    unsigned long i;
    if(at<start || at-start>sizeof(claims) || size>sizeof(claims)-(at-start))
        return FALSE;
    for(i=0;i<size;i++) if(claims[at-start+i]) return FALSE;
    memset(claims+(at-start),1,size);
    return TRUE;
}
static boolean tag_validate_any_claimed(void const *p, unsigned long size) {
    uintptr_t start=(uintptr_t)region.data, at=(uintptr_t)p;
    unsigned long i;
    if(at<start || at-start>sizeof(claims) || size>sizeof(claims)-(at-start))
        return FALSE;
    for(i=0;i<size;i++) if(claims[at-start+i]) return TRUE;
    return FALSE;
}
static void validate_instance(struct tag_validation *v,
    struct tag_validate_instance *instance, short pass) {
    long index=instance-tags;
    if(!instance->base_address) return;
    if(pass==_pass_values) {
        values_seen[index]++;
        /* A repeated canonical subtree must NOT be revisited. A different
         * root sharing arbitrary child bytes must still be rejected. */
        if(shared_child && !claim(region.data+1024,32)) v->refused=TRUE;
    } else checks_seen[index]++;
}
#define malloc audit_malloc
#define free audit_free
#include "production_alias.inc"
#undef malloc
#undef free
#define CHECK(x) do { if(!(x)){fprintf(stderr,"line %d: %s\n",__LINE__,#x); return 1;} } while(0)

static void reset(long count, int custom) {
    long i;
    memset(&validation,0,sizeof(validation));
    memset(claims,0,sizeof(claims));
    memset(tags,0,sizeof(tags));
    memset(values_seen,0,sizeof(values_seen));
    memset(checks_seen,0,sizeof(checks_seen));
    validation.region=region.data;
    validation.region_size=sizeof(region.data);
    tag_validate_globals.custom_edition=custom;
    deny_allocation=shared_child=0;
    for(i=0;i<count;i++) {
        tags[i].group_tag='vehi';
        tags[i].parent_group_tags[0]=tags[i].parent_group_tags[1]=NONE;
        tags[i].tag_index=i;
        tags[i].name="fixture";
        tags[i].base_address=region.data+128;
    }
}
int main(void) {
    int i;
    reset(3,1); shared_child=1;
    CHECK(validate_tag_table(&validation,tags,3));
    CHECK(values_seen[0]==1 && checks_seen[0]==1);
    CHECK(validation.corrections==0);
    CHECK(values_seen[1]==0 && checks_seen[1]==0 && values_seen[2]==0);
    CHECK(allocations==0);

    reset(2,1); tags[0].group_tag=tags[1].group_tag='weap';
    CHECK(validate_tag_table(&validation,tags,2));
    CHECK(values_seen[0]==1 && values_seen[1]==0 && allocations==0);

    reset(2,0); /* Xbox rules unchanged: shared roots remain invalid. */
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); tags[1].base_address=region.data+144;
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); tags[1].group_tag='weap';
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); tags[0].group_tag=tags[1].group_tag='bitm';
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); tags[1].base_address=region.data+256; shared_child=1;
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); tags[1].base_address=region.data+256;
    CHECK(validate_tag_table(&validation,tags,2));
    CHECK(values_seen[0]==1 && values_seen[1]==1 && allocations==0);

    reset(1,1); tags[0].base_address=region.data+8180;
    CHECK(!validate_tag_table(&validation,tags,1) && allocations==0);

    reset(2,1); tags[1].tag_index=9;
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(2,1); deny_allocation=1;
    CHECK(!validate_tag_table(&validation,tags,2) && allocations==0);

    reset(1024,1); shared_child=1;
    CHECK(validate_tag_table(&validation,tags,1024));
    CHECK(values_seen[0]==1 && checks_seen[0]==1 && allocations==0);
    for(i=1;i<1024;i++) CHECK(values_seen[i]==0 && checks_seen[i]==0);
    puts("PASS exact CE tag root aliases: canonical validation, malicious overlaps, OOM, cleanup");
    return 0;
}
