#pragma once

#include <gui/scene_manager.h>

// Generate scene id and callbacks
#define ADD_SCENE(prefix, name, id) LaundR##name##SceneId,
typedef enum {
#include "laundr_scenes_config.h"
    LaundRSceneNum,
} LaundRSceneId;
#undef ADD_SCENE

extern const SceneManagerHandlers laundr_scene_handlers;

// Scene callbacks
void laundr_scene_start_on_enter(void* context);
bool laundr_scene_start_on_event(void* context, SceneManagerEvent event);
void laundr_scene_start_on_exit(void* context);

void laundr_scene_load_on_enter(void* context);
bool laundr_scene_load_on_event(void* context, SceneManagerEvent event);
void laundr_scene_load_on_exit(void* context);

void laundr_scene_emulate_on_enter(void* context);
bool laundr_scene_emulate_on_event(void* context, SceneManagerEvent event);
void laundr_scene_emulate_on_exit(void* context);

void laundr_scene_log_on_enter(void* context);
bool laundr_scene_log_on_event(void* context, SceneManagerEvent event);
void laundr_scene_log_on_exit(void* context);
