package ca.northline.food.application;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader (quoted fields, doubled quotes, CRLF / LF, UTF-8 BOM) for menu imports. */
final class KitchenCsv {
    private KitchenCsv() {}

    static List<List<String>> parse(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        var s = text.startsWith("﻿") ? text.substring(1) : text;
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            boolean next = i + 1 < s.length();
            if (quoted) {
                if (ch == '"' && next && s.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && next && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else {
                cell.append(ch);
            }
            i++;
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }
}
