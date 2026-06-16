package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.config.ModConfigSchema;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModConfigAdapter extends RecyclerView.Adapter<ModConfigAdapter.ConfigViewHolder> {
    private final JsonElement root;
    private final ModConfigSchema rootSchema;
    private final List<Node> nodes = new ArrayList<>();
    private final Set<String> collapsedPaths = new HashSet<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private Context context;

    public ModConfigAdapter(JsonElement root, ModConfigSchema rootSchema) {
        this.root = root == null ? new JsonObject() : root;
        this.rootSchema = rootSchema;
        rebuildNodes();
    }

    public JsonElement getRoot() {
        return root;
    }

    public String validate() {
        return validateNode("$", root, rootSchema);
    }

    @NonNull
    @Override
    public ConfigViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod_config_node, parent, false);
        return new ConfigViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConfigViewHolder holder, int position) {
        holder.bind(nodes.get(position));
    }

    @Override
    public int getItemCount() {
        return nodes.size();
    }

    private void rebuildNodes() {
        nodes.clear();
        if (root != null && root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                ModConfigSchema childSchema = rootSchema == null ? null : rootSchema.getProperty(entry.getKey());
                buildNodes(entry.getKey(), entry.getKey(), entry.getValue(), childSchema, 0, root, entry.getKey());
            }
        } else if (root != null && root.isJsonArray()) {
            ModConfigSchema itemSchema = rootSchema == null ? null : rootSchema.getItems();
            JsonArray array = root.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                buildNodes("item " + (i + 1), "[" + (i + 1) + "]", array.get(i), itemSchema,
                        0, root, String.valueOf(i));
            }
        } else {
            buildNodes("value", "value", root, rootSchema, 0, null, null);
        }
    }

    private void buildNodes(String path, String key, JsonElement value, ModConfigSchema schema,
                            int depth, JsonElement parent, String childKey) {
        Node node = new Node(path, key, value, schema, depth, parent, childKey);
        node.expanded = !collapsedPaths.contains(path);
        nodes.add(node);

        if (!node.expanded) {
            return;
        }

        String type = node.type();
        if ("object".equals(type) && value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                ModConfigSchema childSchema = schema == null ? null : schema.getProperty(entry.getKey());
                buildNodes(path + "." + entry.getKey(), entry.getKey(), entry.getValue(), childSchema,
                        depth + 1, value, entry.getKey());
            }
        } else if ("array".equals(type) && value != null && value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            ModConfigSchema itemSchema = schema == null ? null : schema.getItems();
            for (int i = 0; i < array.size(); i++) {
                buildNodes(path + "[" + (i + 1) + "]", "[" + (i + 1) + "]", array.get(i), itemSchema,
                        depth + 1, value, String.valueOf(i));
            }
        }
    }

    private String validateNode(String path, JsonElement value, ModConfigSchema schema) {
        if (schema == null) {
            return null;
        }

        String type = schema.inferType(value);
        if ("number".equals(type) || "integer".equals(type)) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return getValidationMessage(R.string.mod_config_error_number, path);
            }
            double number = value.getAsDouble();
            if (schema.getMinimum() != null && number < schema.getMinimum()) {
                return getValidationMessage(R.string.mod_config_error_minimum, path, schema.getMinimum());
            }
            if (schema.getMaximum() != null && number > schema.getMaximum()) {
                return getValidationMessage(R.string.mod_config_error_maximum, path, schema.getMaximum());
            }
        } else if ("boolean".equals(type)) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                return getValidationMessage(R.string.mod_config_error_boolean, path);
            }
        } else if ("string".equals(type)) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return getValidationMessage(R.string.mod_config_error_string, path);
            }
        } else if ("object".equals(type) && value != null && value.isJsonObject()) {
            for (Map.Entry<String, ModConfigSchema> entry : schema.getProperties().entrySet()) {
                if (value.getAsJsonObject().has(entry.getKey())) {
                    String error = validateNode(path + "." + entry.getKey(),
                            value.getAsJsonObject().get(entry.getKey()), entry.getValue());
                    if (error != null) {
                        return error;
                    }
                }
            }
        } else if ("array".equals(type) && value != null && value.isJsonArray() && schema.getItems() != null) {
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                String error = validateNode(path + "[" + i + "]", array.get(i), schema.getItems());
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private String getValidationMessage(int resId, Object... args) {
        if (context == null) {
            return String.valueOf(args[0]);
        }
        return context.getString(resId, args);
    }

    private void setValue(Node node, JsonElement value) {
        if (node.parent == null) {
            if (root.isJsonObject() && value.isJsonObject()) {
                root.getAsJsonObject().entrySet().clear();
                for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                    root.getAsJsonObject().add(entry.getKey(), entry.getValue());
                }
            } else if (root.isJsonArray() && value.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                while (array.size() > 0) {
                    array.remove(0);
                }
                for (JsonElement element : value.getAsJsonArray()) {
                    array.add(element);
                }
            }
            rebuildNodes();
            notifyDataSetChanged();
            return;
        }
        if (node.parent.isJsonObject()) {
            node.parent.getAsJsonObject().add(node.childKey, value);
        } else if (node.parent.isJsonArray()) {
            JsonArray array = node.parent.getAsJsonArray();
            int index = Integer.parseInt(node.childKey);
            array.set(index, value);
        }
        rebuildNodes();
        notifyDataSetChanged();
    }

    private JsonElement defaultArrayValue(ModConfigSchema schema) {
        ModConfigSchema itemSchema = schema == null ? null : schema.getItems();
        if (itemSchema != null && itemSchema.getDefaultValue() != null) {
            return itemSchema.getDefaultValue().deepCopy();
        }
        String type = itemSchema == null ? "string" : itemSchema.inferType(null);
        if ("object".equals(type)) return new JsonObject();
        if ("array".equals(type)) return new JsonArray();
        if ("boolean".equals(type)) return new JsonPrimitive(false);
        if ("number".equals(type) || "integer".equals(type)) return new JsonPrimitive(0);
        return new JsonPrimitive("");
    }

    private void showRawJsonEditor(Node node) {
        EditText editText = new EditText(node.itemView.getContext());
        editText.setMinLines(8);
        editText.setTextSize(13);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setText(gson.toJson(node.value));

        new CustomAlertDialog(node.itemView.getContext())
                .setTitleText(node.itemView.getContext().getString(R.string.mod_config_raw_json))
                .setCustomView(editText)
                .setPositiveButton(node.itemView.getContext().getString(R.string.save), v -> {
                    try {
                        setValue(node, JsonParser.parseString(editText.getText().toString()));
                    } catch (RuntimeException e) {
                        new CustomAlertDialog(node.itemView.getContext())
                                .setTitleText(node.itemView.getContext().getString(R.string.mod_config_invalid_json))
                                .setMessage(e.getMessage())
                                .setPositiveButton(node.itemView.getContext().getString(R.string.dialog_positive_ok), null)
                                .show();
                    }
                })
                .setNegativeButton(node.itemView.getContext().getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    class ConfigViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout container;
        private final TextView title;
        private final TextView subtitle;
        private final TextView meta;
        private final LinearLayout controls;

        ConfigViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.node_container);
            title = itemView.findViewById(R.id.node_title);
            subtitle = itemView.findViewById(R.id.node_subtitle);
            meta = itemView.findViewById(R.id.node_meta);
            controls = itemView.findViewById(R.id.node_controls);
        }

        void bind(Node node) {
            node.itemView = itemView;
            int densityIndent = (int) (12 * itemView.getResources().getDisplayMetrics().density);
            int horizontalPadding = (int) (4 * itemView.getResources().getDisplayMetrics().density);
            int verticalPadding = (int) (8 * itemView.getResources().getDisplayMetrics().density);
            container.setPadding(horizontalPadding + node.depth * densityIndent, verticalPadding,
                    horizontalPadding, verticalPadding);
            title.setText(node.label());
            subtitle.setText(node.subtitle());
            subtitle.setVisibility(node.subtitle().isEmpty() ? View.GONE : View.VISIBLE);
            meta.setText(node.meta());
            meta.setVisibility(node.meta().isEmpty() ? View.GONE : View.VISIBLE);
            controls.removeAllViews();

            PersonalizationManager pm = new PersonalizationManager(itemView.getContext());
            pm.applyGlassToView(itemView);
            pm.applyAccentToView(itemView, itemView.getContext());

            if (node.schema != null && node.schema.isReadOnly()) {
                addReadOnly(node);
                return;
            }

            if (node.schema != null && node.schema.hasEnumValues()) {
                addEnum(node);
                return;
            }

            String type = node.type();
            if ("object".equals(type)) {
                addGroupButton(node);
            } else if ("array".equals(type)) {
                addArrayControls(node);
            } else if ("boolean".equals(type)) {
                addSwitch(node);
            } else if ("number".equals(type) || "integer".equals(type)) {
                addEditText(node, true);
            } else {
                addEditText(node, false);
            }
        }

        private void addReadOnly(Node node) {
            TextView value = new TextView(itemView.getContext());
            value.setText(gson.toJson(node.value));
            value.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.on_surface));
            value.setTextSize(13);
            controls.addView(value);
        }

        private void addGroupButton(Node node) {
            addRawJsonButton(node);
            addExpandButton(node);
        }

        private void addExpandButton(Node node) {
            ImageButton button = new ImageButton(itemView.getContext());
            button.setImageResource(R.drawable.ic_arrow_down);
            button.setColorFilter(ContextCompat.getColor(itemView.getContext(), R.color.text_secondary));
            button.setBackgroundResource(android.R.drawable.list_selector_background);
            button.setRotation(node.expanded ? 0f : -90f);
            button.setContentDescription(node.expanded
                    ? itemView.getContext().getString(R.string.mod_config_collapse)
                    : itemView.getContext().getString(R.string.mod_config_expand));
            int size = (int) (32 * itemView.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart((int) (8 * itemView.getResources().getDisplayMetrics().density));
            button.setLayoutParams(params);
            button.setPadding(
                    (int) (8 * itemView.getResources().getDisplayMetrics().density),
                    (int) (8 * itemView.getResources().getDisplayMetrics().density),
                    (int) (8 * itemView.getResources().getDisplayMetrics().density),
                    (int) (8 * itemView.getResources().getDisplayMetrics().density)
            );
            button.setOnClickListener(v -> {
                if (node.expanded) {
                    collapsedPaths.add(node.path);
                } else {
                    collapsedPaths.remove(node.path);
                }
                rebuildNodes();
                notifyDataSetChanged();
            });
            controls.addView(button);
        }

        private void addArrayControls(Node node) {
            addRawJsonButton(node);

            if (node.parent != null && node.parent.isJsonArray()) {
                Button removeButton = smallButton(itemView.getContext().getString(R.string.remove));
                removeButton.setOnClickListener(v -> {
                    JsonArray array = node.parent.getAsJsonArray();
                    array.remove(Integer.parseInt(node.childKey));
                    rebuildNodes();
                    notifyDataSetChanged();
                });
                controls.addView(removeButton);
                addExpandButton(node);
                return;
            }

            Button addButton = smallButton(itemView.getContext().getString(R.string.add));
            addButton.setOnClickListener(v -> {
                if (node.value != null && node.value.isJsonArray()) {
                    node.value.getAsJsonArray().add(defaultArrayValue(node.schema));
                    rebuildNodes();
                    notifyDataSetChanged();
                }
            });
            controls.addView(addButton);
            addExpandButton(node);
        }

        private void addRawJsonButton(Node node) {
            Button jsonButton = smallButton(itemView.getContext().getString(R.string.mod_config_raw_json_short));
            jsonButton.setOnClickListener(v -> showRawJsonEditor(node));
            controls.addView(jsonButton);
        }

        private void addSwitch(Node node) {
            Switch switchView = new Switch(itemView.getContext());
            switchView.setChecked(node.value != null && node.value.isJsonPrimitive() && node.value.getAsBoolean());
            switchView.setOnCheckedChangeListener((buttonView, isChecked) -> setValue(node, new JsonPrimitive(isChecked)));
            new PersonalizationManager(itemView.getContext()).applyAccentToView(switchView, itemView.getContext());
            controls.addView(switchView);
        }

        private void addEnum(Node node) {
            List<String> labels = new ArrayList<>();
            int selected = 0;
            for (int i = 0; i < node.schema.getEnumValues().size(); i++) {
                JsonElement enumValue = node.schema.getEnumValues().get(i);
                labels.add(enumValue.isJsonPrimitive() ? enumValue.getAsString() : gson.toJson(enumValue));
                if (enumValue.equals(node.value)) {
                    selected = i;
                }
            }
            Spinner spinner = new Spinner(itemView.getContext());
            spinner.setBackgroundResource(R.drawable.bg_spinner_outline);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(itemView.getContext(), R.layout.spinner_item, labels);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(selected);
            spinner.setMinimumWidth((int) (180 * itemView.getResources().getDisplayMetrics().density));
            spinner.setMinimumHeight((int) (36 * itemView.getResources().getDisplayMetrics().density));
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    JsonElement newValue = node.schema.getEnumValues().get(position).deepCopy();
                    if (!newValue.equals(node.value)) {
                        setValue(node, newValue);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            controls.addView(spinner);
        }

        private void addEditText(Node node, boolean numeric) {
            EditText editText = new EditText(itemView.getContext());
            editText.setSingleLine(true);
            editText.setMinWidth((int) (180 * itemView.getResources().getDisplayMetrics().density));
            editText.setMinHeight((int) (36 * itemView.getResources().getDisplayMetrics().density));
            editText.setTextSize(13);
            editText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.on_surface));
            editText.setBackgroundResource(R.drawable.bg_spinner_outline);
            int horizontalPadding = (int) (12 * itemView.getResources().getDisplayMetrics().density);
            int verticalPadding = (int) (4 * itemView.getResources().getDisplayMetrics().density);
            editText.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            editText.setInputType(numeric
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                    : InputType.TYPE_CLASS_TEXT);
            editText.setText(node.value == null || node.value.isJsonNull()
                    ? ""
                    : node.value.isJsonPrimitive() ? node.value.getAsString() : gson.toJson(node.value));
            editText.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    commitText(node, editText.getText().toString(), numeric);
                }
            });
            editText.setOnEditorActionListener((v, actionId, event) -> {
                commitText(node, editText.getText().toString(), numeric);
                editText.clearFocus();
                return true;
            });
            controls.addView(editText);
        }

        private void commitText(Node node, String text, boolean numeric) {
            try {
                JsonElement value = numeric
                        ? new JsonPrimitive(Double.parseDouble(text))
                        : new JsonPrimitive(text);
                if (!value.equals(node.value)) {
                    setValue(node, value);
                }
            } catch (NumberFormatException e) {
                editError(e.getMessage());
            }
        }

        private void editError(String message) {
            new CustomAlertDialog(itemView.getContext())
                    .setTitleText(itemView.getContext().getString(R.string.mod_config_validation_failed))
                    .setMessage(message)
                    .setPositiveButton(itemView.getContext().getString(R.string.dialog_positive_ok), null)
                    .show();
        }

        private Button smallButton(String text) {
            Button button = new Button(itemView.getContext());
            button.setText(text);
            button.setAllCaps(false);
            button.setTextSize(11);
            button.setMinHeight((int) (32 * itemView.getResources().getDisplayMetrics().density));
            button.setMinWidth(0);
            button.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.on_primary));
            button.setBackgroundResource(R.drawable.bg_launch_button);
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(itemView.getContext(), R.color.primary)));
            int horizontalPadding = (int) (12 * itemView.getResources().getDisplayMetrics().density);
            int verticalPadding = 0;
            button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (int) (32 * itemView.getResources().getDisplayMetrics().density)
            );
            params.setMarginStart((int) (8 * itemView.getResources().getDisplayMetrics().density));
            button.setLayoutParams(params);
            new PersonalizationManager(itemView.getContext()).applyAccentToView(button, itemView.getContext());
            return button;
        }
    }

    static class Node {
        final String path;
        final String key;
        JsonElement value;
        final ModConfigSchema schema;
        final int depth;
        final JsonElement parent;
        final String childKey;
        boolean expanded = true;
        View itemView;

        Node(String path, String key, JsonElement value, ModConfigSchema schema, int depth,
             JsonElement parent, String childKey) {
            this.path = path;
            this.key = key;
            this.value = value == null ? JsonNull.INSTANCE : value;
            this.schema = schema;
            this.depth = depth;
            this.parent = parent;
            this.childKey = childKey;
        }

        String type() {
            return schema == null ? ModConfigSchema.inferTypeFromValue(value) : schema.inferType(value);
        }

        String label() {
            if (schema != null && schema.getTitle() != null && !schema.getTitle().trim().isEmpty()) {
                return schema.getTitle();
            }
            if (key.startsWith("[") && key.endsWith("]")) {
                return itemView == null
                        ? "Item " + key.substring(1, key.length() - 1)
                        : itemView.getContext().getString(R.string.mod_config_item_label, key.substring(1, key.length() - 1));
            }
            return formatKey(key);
        }

        String subtitle() {
            if (schema != null && schema.getDescription() != null && !schema.getDescription().trim().isEmpty()) {
                return schema.getDescription();
            }

            String type = type();
            if ("object".equals(type)) {
                return itemView == null ? "" : itemView.getContext().getString(R.string.mod_config_object_desc);
            }
            if ("array".equals(type)) {
                return itemView == null ? "" : itemView.getContext().getString(R.string.mod_config_array_desc);
            }
            return "";
        }

        String meta() {
            String type = type();
            StringBuilder builder = new StringBuilder();
            if ("number".equals(type) || "integer".equals(type)) {
                if (schema != null && (schema.getMinimum() != null || schema.getMaximum() != null)) {
                    if (itemView != null) {
                        builder.append(itemView.getContext().getString(R.string.mod_config_range_prefix));
                        builder.append(' ');
                    } else {
                        builder.append("Range ");
                    }
                    builder.append(schema.getMinimum() == null ? "-∞" : trimNumber(schema.getMinimum()));
                    builder.append(" - ");
                    builder.append(schema.getMaximum() == null ? "+∞" : trimNumber(schema.getMaximum()));
                }
            } else if (schema != null && schema.hasEnumValues()) {
                if (itemView != null) {
                    builder.append(itemView.getContext().getResources().getQuantityString(
                            R.plurals.mod_config_choices_count,
                            schema.getEnumValues().size(),
                            schema.getEnumValues().size()));
                } else {
                    builder.append(schema.getEnumValues().size()).append(" choices");
                }
            } else if ("array".equals(type) && value != null && value.isJsonArray()) {
                int count = value.getAsJsonArray().size();
                if (itemView != null) {
                    builder.append(itemView.getContext().getResources().getQuantityString(
                            R.plurals.mod_config_items_count,
                            count,
                            count));
                } else {
                    builder.append(count).append(" items");
                }
            }
            return builder.toString();
        }

        private static String trimNumber(Double value) {
            if (value == null) {
                return "";
            }
            if (Math.rint(value) == value) {
                return String.valueOf(value.longValue());
            }
            return String.valueOf(value);
        }

        private static String formatKey(String raw) {
            if (raw == null || raw.isEmpty()) {
                return "Setting";
            }
            String spaced = raw.replace('_', ' ').replace('-', ' ');
            StringBuilder out = new StringBuilder();
            boolean upperNext = true;
            for (int i = 0; i < spaced.length(); i++) {
                char c = spaced.charAt(i);
                if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(spaced.charAt(i - 1))) {
                    out.append(' ');
                }
                if (Character.isWhitespace(c)) {
                    out.append(' ');
                    upperNext = true;
                } else if (upperNext) {
                    out.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    out.append(c);
                }
            }
            return out.toString().trim();
        }
    }
}
