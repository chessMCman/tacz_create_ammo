package com.plr.taczcreate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans every loaded TaCZ gun pack (default + installed) for ammo recipes and generates an
 * equivalent Create mixing recipe for each ammo type, reusing the gun pack's own material costs so
 * the balance stays faithful to the pack author's intent.
 *
 * <p>This code must never throw: a malformed or exotic recipe from a third-party gun pack should be
 * skipped quietly instead of crashing the recipe load.</p>
 */
public final class AutoAmmoRecipes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RECIPE_TYPE = "tacz:gun_smith_table_crafting";
    private static final int MAX_INGREDIENT_ENTRIES = 8;
    private static final Gson GSON = new Gson();

    private AutoAmmoRecipes() {
    }

    public static void inject(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager) {
        // 1. Collect the real stack size for every ammo from the loaded `index/ammo` data.
        Map<String, Integer> stackSizes = new HashMap<>();
        try {
            for (Map.Entry<ResourceLocation, Resource> entry : resourceManager
                    .listResources("index/ammo", id -> id.getPath().endsWith(".json")).entrySet()) {
                try {
                    String path = entry.getKey().getPath(); // "index/ammo/9mm.json"
                    if (!path.startsWith("index/ammo/") || !path.endsWith(".json")) continue;
                    String name = path.substring("index/ammo/".length(), path.length() - ".json".length());
                    try (Reader reader = entry.getValue().openAsReader()) {
                        JsonObject index = GSON.fromJson(reader, JsonObject.class);
                        if (index != null && index.has("stack_size")) {
                            int stack = index.getAsJsonPrimitive("stack_size").getAsInt();
                            stackSizes.put(name, stack);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Snapshot the gun-smith AMMO recipes that were already loaded into the recipe map.
        List<Map.Entry<ResourceLocation, JsonElement>> ammoRecipes = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : object.entrySet()) {
            try {
                JsonElement element = entry.getValue();
                if (!element.isJsonObject()) continue;
                JsonObject recipe = element.getAsJsonObject();
                if (!RECIPE_TYPE.equals(asString(recipe.get("type")))) continue;
                JsonObject result = recipe.has("result") && recipe.get("result").isJsonObject()
                        ? recipe.getAsJsonObject("result") : null;
                if (result == null || !"ammo".equals(asString(result.get("type")))) continue;
                ammoRecipes.add(entry);
            } catch (Exception ignored) {
            }
        }

        // 3. Generate a Create mixing recipe for each ammo (never let one bad recipe crash the load).
        for (Map.Entry<ResourceLocation, JsonElement> entry : ammoRecipes) {
            try {
                JsonObject gunSmith = entry.getValue().getAsJsonObject();
                JsonObject result = gunSmith.getAsJsonObject("result");
                String ammoId = asString(result.get("id"));
                if (ammoId == null) continue;
                int resultCount = getInt(result, "count", 1);
                String ammoName = ammoId.contains(":") ? ammoId.substring(ammoId.indexOf(':') + 1) : ammoId;
                int stackSize = stackSizes.getOrDefault(ammoName, 64);

                JsonObject mixing = buildMixingRecipe(gunSmith, ammoId, ammoName, resultCount, stackSize);
                object.put(ResourceLocation.fromNamespaceAndPath("tacz_create_ammo", "auto/" + ammoName), mixing);
            } catch (Exception e) {
                LOGGER.warn("[tacz_create_ammo] Skipping ammo recipe '{}' ({})", entry.getKey(), e.toString());
            }
        }
    }

    private static JsonObject buildMixingRecipe(JsonObject gunSmith, String ammoId, String ammoName, int resultCount, int stackSize) {
        record Material(String thing, boolean tag, int count) {
        }
        List<Material> list = new ArrayList<>();
        int total = 0;

        JsonArray materials = gunSmith.has("materials") && gunSmith.get("materials").isJsonArray()
                ? gunSmith.getAsJsonArray("materials") : null;
        if (materials != null) {
            for (JsonElement m : materials) {
                if (!m.isJsonObject()) continue;
                JsonObject mat = m.getAsJsonObject();
                JsonElement itemEl = mat.get("item");
                int count = getInt(mat, "count", 1);
                if (count <= 0) count = 1;

                String tag = null;
                String item = null;
                if (itemEl != null && itemEl.isJsonObject()) {
                    JsonObject io = itemEl.getAsJsonObject();
                    tag = asString(io.get("tag"));
                    if (tag == null) {
                        JsonElement iv = io.get("item");
                        if (iv != null && iv.isJsonPrimitive()) {
                            item = iv.getAsString();
                        } else if (iv != null && iv.isJsonObject() && iv.getAsJsonObject().has("id")) {
                            item = asString(iv.getAsJsonObject().get("id"));
                        }
                    }
                }

                if (tag == null && item == null) continue; // unsupported ingredient form -> skip
                list.add(new Material(tag != null ? tag : item, tag != null, count));
                total += count;
            }
        }

        // Fallback for ammo with no parsable materials.
        if (list.isEmpty()) {
            list.add(new Material("minecraft:copper_ingot", false, 4));
            list.add(new Material("minecraft:gunpowder", false, 2));
            total = 6;
        }

        double scale = total > MAX_INGREDIENT_ENTRIES ? (double) MAX_INGREDIENT_ENTRIES / total : 1.0;
        int scaledOut = Math.max(1, (int) Math.round(resultCount * scale));

        JsonArray ingredients = new JsonArray();
        for (Material material : list) {
            int scaled = Math.max(1, (int) Math.round(material.count * scale));
            for (int i = 0; i < scaled; i++) {
                JsonObject ingredient = new JsonObject();
                ingredient.addProperty(material.tag ? "tag" : "item", material.thing);
                ingredients.add(ingredient);
            }
        }

        JsonObject mixing = new JsonObject();
        mixing.addProperty("type", "create:mixing");
        mixing.add("ingredients", ingredients);

        JsonObject result = new JsonObject();
        result.addProperty("id", "tacz:ammo");
        result.addProperty("count", scaledOut);

        JsonObject components = new JsonObject();
        JsonObject customData = new JsonObject();
        customData.addProperty("AmmoId", ammoId);
        components.add("minecraft:custom_data", customData);
        components.addProperty("minecraft:max_stack_size", stackSize);
        result.add("components", components);

        JsonArray results = new JsonArray();
        results.add(result);
        mixing.add("results", results);
        return mixing;
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && ((JsonPrimitive) element).isString()
                ? element.getAsString() : null;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && ((JsonPrimitive) element).isNumber()
                ? element.getAsInt() : fallback;
    }
}