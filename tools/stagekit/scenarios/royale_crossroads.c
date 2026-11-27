/* Original rural settlement, baked into the world's render BSP. The collision
   scenery instances share a palette entry for every identical solid shape. */
#include "../stagekit.h"
#include "shaders/shader_definitions.h"

struct paint { char const *name; unsigned long rgb; long count; };
#include "orchard_mesh.inc"

struct ce_vertex { float xyz[3], normal[3], binormal[3], tangent[3], uv[2]; };
struct subcluster { real_rectangle3d bounds; struct tag_block surfaces; };

static long paint_shader(struct paint const *paint)
{
	char name[80];
	void *data;
	struct bitmap_group *bitmap;
	struct bitmap_data *image;
	struct shader *shader;
	unsigned long *swatch = cache_bsp_allocate(4);
	long texture, handle;
	sprintf(name, "orchard\\%s", paint->name);
	texture = cache_tag_new(FOURCC('b','i','t','m'), NONE, NONE, name, sizeof(*bitmap), &data);
	bitmap = data;
	image = cache_block(&bitmap->bitmaps, 1, sizeof(*image));
	image->signature = FOURCC('b','i','t','m');
	image->width = image->height = image->depth = 1;
	image->format = 11; /* A8R8G8B8, linear one-texel swatch */
	image->flags = 1;
	image->tag_index = texture;
	image->cache_block_index = NONE;
	image->pixels_offset = cache_bsp_file_offset(swatch);
	image->pixels_size = 4;
	*swatch = 0xff000000 | paint->rgb;
	/* shader_environment and its diffuse base map (tag_schema_render.c). */
	handle = cache_tag_new(FOURCC('s','e','n','v'), FOURCC('s','h','d','r'), NONE, name, 0x344, &data);
	shader = data;
	shader->base.type = 3;
	cache_reference((struct tag_reference *)((byte *)data + 0x88), texture);
	return handle;
}

static void write_face(struct ce_vertex *v, float const points[3][3])
{
	float edge[3], side[3], normal[3], length, tangent_length;
	int i, k;
	for (k=0; k<3; ++k) {
		edge[k] = points[1][k]-points[0][k];
		side[k] = points[2][k]-points[0][k];
	}
	for (k=0; k<3; ++k)
		normal[k] = edge[(k+1)%3]*side[(k+2)%3] - edge[(k+2)%3]*side[(k+1)%3];
	length = square_root(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
	tangent_length = square_root(edge[0]*edge[0]+edge[1]*edge[1]+edge[2]*edge[2]);
	for (k=0; k<3; ++k) { normal[k] /= length; edge[k] /= tangent_length; }
	for (i=0; i<3; ++i) {
		for (k=0; k<3; ++k) {
			v[i].xyz[k] = points[i][k]/3.048f;
			v[i].normal[k] = normal[k];
			v[i].tangent[k] = edge[k];
			v[i].binormal[k] = normal[(k+1)%3]*edge[(k+2)%3] - normal[(k+2)%3]*edge[(k+1)%3];
		}
	}
}

static void make_mesh(struct structure_bsp *bsp)
{
	struct structure_lightmap *light = cache_bsp_block(&bsp->lightmaps, 1, sizeof(*light));
	struct structure_material *mat = cache_bsp_block(&light->materials, NUMBEROF(paints), sizeof(*mat));
	struct structure_surface *surfaces = cache_bsp_block(&bsp->surfaces, NUMBEROF(triangles), sizeof(*surfaces));
	byte *cluster = bsp->clusters.address;
	/* The named block offsets follow structure_visibility_cluster's engine layout. */
	struct subcluster *sub = cache_bsp_block((struct tag_block *)(cluster+0x34), 1, sizeof(*sub));
	long *ids = cache_bsp_block(&sub->surfaces, NUMBEROF(triangles), sizeof(long));
	long *groups = cache_bsp_block((struct tag_block *)(cluster+0x44), NUMBEROF(triangles)+3*NUMBEROF(paints), sizeof(long));
	long material, face=0, i;
	sub->bounds = bsp->world_bounds;
	light->bitmap_index = NONE;
	bsp->default_lighting.ambient_color.r = bsp->default_lighting.ambient_color.g = bsp->default_lighting.ambient_color.b = 1;
	for (material=0; material<NUMBEROF(paints); ++material, ++mat) {
		long count = paints[material].count;
		struct ce_vertex *v = cache_bsp_allocate(count*3*sizeof(*v));
		cache_reference(&mat->shader, paint_shader(&paints[material]));
		mat->first_surface_index = face;
		mat->surface_count = count;
		mat->breakable_surface_index = NONE;
		mat->lighting = bsp->default_lighting;
		mat->vertices.count = count*3;
		mat->uncompressed_vertex_data.address = v;
		mat->uncompressed_vertex_data.size = count*3*sizeof(*v);
		*groups++ = 0; *groups++ = material; *groups++ = count;
		for (i=0; i<count; ++i, ++face) {
			int k;
			write_face(v+i*3, triangles[face]);
			for (k=0; k<3; ++k) surfaces[face].vertex_indices[k] = i*3+k;
			ids[face] = *groups++ = face;
		}
	}
}

static void crossroads_build(struct stage *stage)
{
	short palette[NUMBEROF(solids)];
	long i, j;
	/* Beside the barn's west wall, looking east towards it. */
	real_point3d start = {{ 11.f/3.048f - 1.25f, -33.f/3.048f, 0 }};
	player_start(stage, &start);
	make_mesh(stage->bsp);
	for (i=0; i<NUMBEROF(solids); ++i) {
		const float *b = solids[i];
		real_point3d pos = {{ b[0]/3.048f,b[1]/3.048f,b[2]/3.048f }};
		for (j=0; j<i; ++j)
			if (!memcmp(solids[j]+3,b+3,3*sizeof(float))) break;
		if (j==i) {
			char name[48];
			real_rectangle3d box = {{ 0,b[3]/3.048f,0,b[4]/3.048f,0,b[5]/3.048f }};
			sprintf(name,"orchard\\solid_%ld",i);
			palette[i] = (short)stage->scenario->scenery_palette.count;
			prop_obstacle(stage,name,&box,&pos);
		} else {
			palette[i] = palette[j];
			prop_place_obstacle(stage,palette[i],&pos);
		}
	}
}

struct stagekit_scenario const scenario_royale_crossroads = {
	"royale_crossroads", "Orchard Crossing on the eight-kilometre Battle Royale footprint",
	crossroads_build, ROYALE_HALF_WIDTH, 0x100000 };
