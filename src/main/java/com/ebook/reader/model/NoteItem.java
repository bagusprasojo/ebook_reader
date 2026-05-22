package com.ebook.reader.model;

public record NoteItem(int id, int ebookId, int pageNo, String title, String content, String updatedAt) {}
