package com.lowdragmc.photon;

import lombok.SneakyThrows;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.io.File;

public class DataFixer {

    public static void main(String[] args) {
        doFix(new File("C:\\Projects\\Photon-1.21\\runs\\client\\ldlib2\\assets"));
    }

    @SneakyThrows
    public static void doFix(File ldlib2Dir) {
        if (ldlib2Dir == null || !ldlib2Dir.exists()) {
            return;
        }

        if (ldlib2Dir.isDirectory()) {
            File[] children = ldlib2Dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    doFix(child);
                }
            }
        }

        String originalName = ldlib2Dir.getName();
        String fixedName = originalName.toLowerCase()
                .replace(" ", "_")
                .replace("(","_")
                .replace(")","_");

        if (!originalName.equals(fixedName)) {
            File newFile = new File(ldlib2Dir.getParent(), fixedName);
            if (ldlib2Dir.renameTo(newFile)) {
                System.out.println("Renamed [" + (ldlib2Dir.isDirectory() ? "Dir" : "File") + "]: " + originalName + " -> " + fixedName);
            } else {
                System.err.println("Failed to rename: " + ldlib2Dir.getAbsolutePath());
            }

        }


        if (fixedName.endsWith(".fxproj")) {
            var nbt = NbtIo.read(new File(ldlib2Dir.getParent(), fixedName).toPath());
            traverseNbt(nbt);
            NbtIo.write(nbt, new File(ldlib2Dir.getParent(), fixedName).toPath());
        }
        if (fixedName.endsWith(".fx")) {
            var nbt = NbtIo.readCompressed(new File(ldlib2Dir.getParent(), fixedName).toPath(), NbtAccounter.unlimitedHeap());
            traverseNbt(nbt);
            NbtIo.writeCompressed(nbt, new File(ldlib2Dir.getParent(), fixedName).toPath());
        }
    }

    private static void traverseNbt(net.minecraft.nbt.Tag nbt) {
        if (nbt instanceof net.minecraft.nbt.CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                var value = compound.get(key);
                if (key.equals("resourcePath")) {
                    if (value instanceof net.minecraft.nbt.CompoundTag resourcePath && resourcePath.contains("path")) {
                        resourcePath.putString("path",
                                resourcePath.getString("path").toLowerCase()
                                        .replace(" ", "_")
                                        .replace("(","_")
                                        .replace(")","_")
                        );
                    } else if (value instanceof StringTag stringTag) {
                        compound.putString(key,
                                stringTag.getAsString().toLowerCase()
                                        .replace(" ", "_")
                        );
                    }
                }
                traverseNbt(value);
            }
        } else if (nbt instanceof net.minecraft.nbt.ListTag list) {
            for (net.minecraft.nbt.Tag tag : list) {
                traverseNbt(tag);
            }
        }
    }
}
