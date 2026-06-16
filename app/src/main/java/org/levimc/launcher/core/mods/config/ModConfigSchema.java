package org.levimc.launcher.core.mods.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModConfigSchema {
    private final String title;
    private final String description;
    private final String type;
    private final JsonElement defaultValue;
    private final List<JsonElement> enumValues;
    private final Double minimum;
    private final Double maximum;
    private final boolean readOnly;
    private final Map<String, ModConfigSchema> properties;
    private final ModConfigSchema items;

    private ModConfigSchema(String title, String description, String type, JsonElement defaultValue,
                            List<JsonElement> enumValues, Double minimum, Double maximum, boolean readOnly,
                            Map<String, ModConfigSchema> properties, ModConfigSchema items) {
        this.title = title;
        this.description = description;
        this.type = type;
        this.defaultValue = defaultValue;
        this.enumValues = enumValues == null ? Collections.emptyList() : Collections.unmodifiableList(enumValues);
        this.minimum = minimum;
        this.maximum = maximum;
        this.readOnly = readOnly;
        this.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
        this.items = items;
    }

    public static ModConfigSchema fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String title = getString(object, "title");
        String description = getString(object, "description");
        String type = getString(object, "type");
        JsonElement defaultValue = object.has("default") ? object.get("default") : null;
        Double minimum = getDouble(object, "minimum");
        Double maximum = getDouble(object, "maximum");
        boolean readOnly = object.has("readOnly") && object.get("readOnly").isJsonPrimitive()
                && object.get("readOnly").getAsBoolean();

        List<JsonElement> enumValues = new ArrayList<>();
        if (object.has("enum") && object.get("enum").isJsonArray()) {
            for (JsonElement enumValue : object.getAsJsonArray("enum")) {
                enumValues.add(enumValue.deepCopy());
            }
        }

        Map<String, ModConfigSchema> properties = new LinkedHashMap<>();
        if (object.has("properties") && object.get("properties").isJsonObject()) {
            JsonObject props = object.getAsJsonObject("properties");
            for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                ModConfigSchema propertySchema = fromJson(entry.getValue());
                if (propertySchema != null) {
                    properties.put(entry.getKey(), propertySchema);
                }
            }
        }

        ModConfigSchema items = null;
        if (object.has("items")) {
            items = fromJson(object.get("items"));
        }

        return new ModConfigSchema(title, description, type, defaultValue, enumValues,
                minimum, maximum, readOnly, properties, items);
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public JsonElement getDefaultValue() {
        return defaultValue;
    }

    public List<JsonElement> getEnumValues() {
        return enumValues;
    }

    public Double getMinimum() {
        return minimum;
    }

    public Double getMaximum() {
        return maximum;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public Map<String, ModConfigSchema> getProperties() {
        return properties;
    }

    public ModConfigSchema getItems() {
        return items;
    }

    public ModConfigSchema getProperty(String name) {
        return properties.get(name);
    }

    public boolean hasEnumValues() {
        return !enumValues.isEmpty();
    }

    public String inferType(JsonElement value) {
        if (type != null && !type.trim().isEmpty()) {
            return type.trim().toLowerCase();
        }
        return inferTypeFromValue(value);
    }

    public static String inferTypeFromValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "string";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                return "boolean";
            }
            if (value.getAsJsonPrimitive().isNumber()) {
                return "number";
            }
        }
        return "string";
    }

    private static String getString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Double getDouble(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
