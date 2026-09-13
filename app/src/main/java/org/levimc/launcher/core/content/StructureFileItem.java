package org.levimc.launcher.core.content;

import java.io.File;

/**
 * 结构方块文件（.mcstructure）。这是一个独立文件，不是目录。
 */
public class StructureFileItem extends ContentItem {

    public StructureFileItem(File file) {
        super(file.getName(), file);
    }

    @Override
    public String getType() {
        return "Structure";
    }

    @Override
    public String getDescription() {
        return getFormattedSize();
    }

    @Override
    public boolean isValid() {
        return file != null && file.isFile();
    }
}
