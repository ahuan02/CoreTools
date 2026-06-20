package com.szh.entity;

import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AI 可调用的文件操作工具（@Tool 注解静态方式）
 * <p>
 * 通过 @Tool 注解定义，LangChain4j 可自动生成 ToolSpecification。
 * 适用于固定、标准化的工具方法。
 */
public class FileTools {

    @Tool("创建或覆盖写入一个文件，需要提供文件路径和文件内容")
    public String writeFile(String path, String content) {
        try {
            Path filePath = Paths.get(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return "文件写入成功: " + filePath.toAbsolutePath();
        } catch (IOException e) {
            return "文件写入失败: " + e.getMessage();
        }
    }

    @Tool("读取一个文件的内容，返回文件全部文本")
    public String readFile(String path) {
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            return "文件读取失败: " + e.getMessage();
        }
    }

    @Tool("列出指定目录下的文件和子目录（仅一层）")
    public String listDirectory(String path) {
        try {
            StringBuilder sb = new StringBuilder();
            Path dir = Paths.get(path);
            if (!Files.isDirectory(dir)) {
                return "不是一个目录: " + path;
            }
            try (var stream = Files.list(dir)) {
                stream.sorted((a, b) -> {
                    // 目录优先，然后按名称排序
                    boolean aDir = Files.isDirectory(a), bDir = Files.isDirectory(b);
                    if (aDir != bDir) return aDir ? -1 : 1;
                    return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                }).forEach(p -> {
                    String type = Files.isDirectory(p) ? "📁 " : "📄 ";
                    sb.append(type).append(p.getFileName()).append("\n");
                });
            }
            return sb.isEmpty() ? "目录为空" : sb.toString();
        } catch (IOException e) {
            return "列出目录失败: " + e.getMessage();
        }
    }

    @Tool("递归展开指定目录的完整树状结构，用于查看项目目录布局。maxDepth 控制深度（默认3），避免输出过大")
    public String listTree(String path, String maxDepth) {
        try {
            Path dir = Paths.get(path);
            if (!Files.isDirectory(dir)) {
                return "不是一个目录: " + path;
            }
            int depth = 3;
            try { depth = Integer.parseInt(maxDepth); } catch (NumberFormatException ignored) {}
            if (depth < 1) depth = 1;
            if (depth > 5) depth = 5; // 防止输出爆炸

            StringBuilder sb = new StringBuilder();
            sb.append(dir.toAbsolutePath()).append("\n");
            treeWalk(dir, "", depth, sb);
            return sb.toString();
        } catch (IOException e) {
            return "展开目录树失败: " + e.getMessage();
        }
    }

    /** 递归遍历目录树 */
    private void treeWalk(Path dir, String prefix, int depth, StringBuilder sb) throws IOException {
        if (depth <= 0) return;
        try (var stream = Files.list(dir)) {
            List<Path> children = stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a), bDir = Files.isDirectory(b);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).toList();

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                String connector = isLast ? "└── " : "├── ";
                String childPrefix = isLast ? "    " : "│   ";

                if (Files.isDirectory(child)) {
                    sb.append(prefix).append(connector).append("📁 ").append(child.getFileName()).append("\n");
                    treeWalk(child, prefix + childPrefix, depth - 1, sb);
                } else {
                    sb.append(prefix).append(connector).append("📄 ").append(child.getFileName()).append("\n");
                }
            }
        }
    }
}
