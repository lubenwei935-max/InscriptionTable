package com.example.inscriptiontable.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 一行铭文（由若干同色文本段组成）。
 */
public final class LoreLine {

    public final List<LoreSegment> segments = new ArrayList<LoreSegment>();

    public void add(LoreSegment segment) {
        if (segment != null && segment.text.length() > 0) {
            segments.add(segment);
        }
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}
