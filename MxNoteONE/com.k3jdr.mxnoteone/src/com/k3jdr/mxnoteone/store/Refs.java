package com.k3jdr.mxnoteone.store;

/** Where a section or page is: its notebook and section (Models.swift SectionRef, PageRef). */
public final class Refs {
    private Refs() {}

    public record SectionRef(String notebookId, String sectionId) {}

    public record PageRef(String notebookId, String sectionId, String pageId) {
        public SectionRef section() { return new SectionRef(notebookId, sectionId); }
    }
}
