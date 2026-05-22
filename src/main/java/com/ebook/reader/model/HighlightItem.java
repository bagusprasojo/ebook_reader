package com.ebook.reader.model;

public record HighlightItem(int id, int ebookId, int pageNo, String textBlock, String bboxJson, String color) {}
