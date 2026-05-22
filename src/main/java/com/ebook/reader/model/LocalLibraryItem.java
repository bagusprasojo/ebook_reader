package com.ebook.reader.model;

public record LocalLibraryItem(int ebookId, String title, String author, String packagePath, int totalPages, String status) {}
