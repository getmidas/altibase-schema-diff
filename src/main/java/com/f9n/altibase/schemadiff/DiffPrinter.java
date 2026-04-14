package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.DiffItem;
import com.f9n.altibase.schemadiff.model.DiffResult;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

public class DiffPrinter {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String DIM = "\033[2m";
    private static final String BG_RED = "\033[41m";
    private static final String BG_GREEN = "\033[42m";
    private static final String WHITE = "\033[37m";

    private final PrintStream out;
    private final boolean color;

    public DiffPrinter(PrintStream out, boolean color) {
        this.out = out;
        this.color = color;
    }

    public void print(DiffResult result) {
        printHeader(result);

        if (!result.hasDifferences()) {
            out.println(green("  No differences found. Schemas are identical."));
            out.println();
            return;
        }

        Map<DiffItem.Category, List<DiffItem>> byCategory = result.items().stream()
                .collect(Collectors.groupingBy(DiffItem::category, LinkedHashMap::new, Collectors.toList()));

        for (var entry : byCategory.entrySet()) {
            printCategory(entry.getKey(), entry.getValue());
        }

        printSummary(result);
    }

    private void printHeader(DiffResult result) {
        out.println();
        out.println(bold(cyan("=== Altibase Schema Diff ===")));
        out.println(dim("  Source: ") + result.source().label());
        out.println(dim("  Target: ") + result.target().label());
        out.println(dim("  Source extracted: ") + result.source().extractedAt());
        out.println(dim("  Target extracted: ") + result.target().extractedAt());
        out.println();
    }

    private void printCategory(DiffItem.Category category, List<DiffItem> items) {
        out.println(bold(cyan("--- " + categoryLabel(category) + " ---")));

        Map<String, List<DiffItem>> bySchema = items.stream()
                .collect(Collectors.groupingBy(DiffItem::schema, LinkedHashMap::new, Collectors.toList()));

        for (var entry : bySchema.entrySet()) {
            if (category != DiffItem.Category.SCHEMA) {
                out.println("  " + dim("Schema: " + entry.getKey()));
            }

            for (DiffItem item : entry.getValue()) {
                printItem(item, category == DiffItem.Category.SCHEMA);
            }
        }
        out.println();
    }

    private void printItem(DiffItem item, boolean isSchema) {
        String indent = isSchema ? "  " : "    ";
        String name = item.objectName();

        switch (item.type()) {
            case ONLY_IN_SOURCE -> out.println(indent + red("- " + name) + dim(" [source only]"));
            case ONLY_IN_TARGET -> out.println(indent + green("+ " + name) + dim(" [target only]"));
            case DIFFERENT -> {
                out.println(indent + yellow("~ " + name) + dim(" [different]"));
                for (String detail : item.details()) {
                    out.println(indent + "    " + colorizeDetail(detail));
                }
                if (item.hasSourceDiff()) {
                    printUnifiedDiff(indent + "    ", item.schema() + "." + name, item.sourceText(), item.targetText());
                }
            }
        }
    }

    private void printUnifiedDiff(String indent, String name, String sourceText, String targetText) {
        List<String> sourceLines = toLines(sourceText);
        List<String> targetLines = toLines(targetText);

        Patch<String> patch = DiffUtils.diff(sourceLines, targetLines);
        if (patch.getDeltas().isEmpty()) return;

        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                "source:" + name, "target:" + name,
                sourceLines, patch, 3
        );

        out.println();
        for (String line : unifiedDiff) {
            if (line.startsWith("---")) {
                out.println(indent + bold(red(line)));
            } else if (line.startsWith("+++")) {
                out.println(indent + bold(green(line)));
            } else if (line.startsWith("@@")) {
                out.println(indent + bold(magenta(line)));
            } else if (line.startsWith("-")) {
                out.println(indent + red(line));
            } else if (line.startsWith("+")) {
                out.println(indent + green(line));
            } else {
                out.println(indent + dim(line));
            }
        }
        out.println();
    }

    private static List<String> toLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return List.of(text.split("\n", -1));
    }

    private void printSummary(DiffResult result) {
        out.println(bold(cyan("--- Summary ---")));
        long total = result.items().size();
        long onlySource = result.countByType(DiffItem.Type.ONLY_IN_SOURCE);
        long onlyTarget = result.countByType(DiffItem.Type.ONLY_IN_TARGET);
        long different = result.countByType(DiffItem.Type.DIFFERENT);

        out.println("  Total differences: " + bold(String.valueOf(total)));
        if (onlySource > 0) out.println("    " + red(onlySource + " only in source"));
        if (onlyTarget > 0) out.println("    " + green(onlyTarget + " only in target"));
        if (different > 0) out.println("    " + yellow(different + " different"));

        out.println();
        out.println("  By category:");
        for (DiffItem.Category cat : DiffItem.Category.values()) {
            long count = result.countByCategory(cat);
            if (count > 0) {
                out.println("    " + categoryLabel(cat) + ": " + count);
            }
        }
        out.println();
    }

    private static String categoryLabel(DiffItem.Category cat) {
        return switch (cat) {
            case SCHEMA -> "Schemas";
            case TABLE -> "Tables";
            case COLUMN -> "Columns";
            case PROCEDURE -> "Stored Procedures";
            case FUNCTION -> "Functions";
            case SEQUENCE -> "Sequences";
            case VIEW -> "Views";
        };
    }

    private String colorizeDetail(String detail) {
        if (detail.startsWith("+ ")) return green(detail);
        if (detail.startsWith("- ")) return red(detail);
        if (detail.startsWith("~ ")) return yellow(detail);
        return dim(detail);
    }

    private String bold(String s) { return color ? BOLD + s + RESET : s; }
    private String red(String s) { return color ? RED + s + RESET : s; }
    private String green(String s) { return color ? GREEN + s + RESET : s; }
    private String yellow(String s) { return color ? YELLOW + s + RESET : s; }
    private String cyan(String s) { return color ? CYAN + s + RESET : s; }
    private String magenta(String s) { return color ? MAGENTA + s + RESET : s; }
    private String dim(String s) { return color ? DIM + s + RESET : s; }
}
