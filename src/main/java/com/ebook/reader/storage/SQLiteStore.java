package com.ebook.reader.storage;

import com.ebook.reader.model.BookmarkItem;
import com.ebook.reader.model.LocalLibraryItem;
import com.ebook.reader.model.NoteItem;
import com.ebook.reader.model.AnnotationItem;
import com.ebook.reader.model.AnnotationPageItem;
import com.ebook.reader.model.HighlightItem;
import com.ebook.reader.model.HighlightPageItem;
import com.ebook.reader.model.ReadingProgressItem;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SQLiteStore {
    private final String jdbcUrl;

    public SQLiteStore(Path dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void init() throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            try {
                st.executeUpdate("PRAGMA journal_mode=WAL");
            } catch (SQLException walError) {
                // Some Windows environments fail creating/resizing .db-shm; fallback keeps app usable.
                st.executeUpdate("PRAGMA journal_mode=DELETE");
            }
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS local_library (
                    ebook_id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL,
                    author TEXT,
                    package_path TEXT NOT NULL,
                    total_pages INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reading_progress (
                    ebook_id INTEGER PRIMARY KEY,
                    current_page INTEGER NOT NULL,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ebook_id INTEGER NOT NULL,
                    page_no INTEGER NOT NULL,
                    label TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ebook_id INTEGER NOT NULL,
                    page_no INTEGER NOT NULL,
                    title TEXT,
                    content TEXT NOT NULL,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS highlights (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ebook_id INTEGER NOT NULL,
                    page_no INTEGER NOT NULL,
                    text_block TEXT NOT NULL,
                    bbox_json TEXT,
                    color TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS annotations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ebook_id INTEGER NOT NULL,
                    page_no INTEGER NOT NULL,
                    stroke_json TEXT NOT NULL,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS security_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    detail TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }

        ensureColumn("notes", "title", "TEXT");
        ensureColumn("notes", "updated_at", "TEXT DEFAULT CURRENT_TIMESTAMP");
        ensureColumn("highlights", "bbox_json", "TEXT");
        ensureColumn("highlights", "color", "TEXT");
    }

    private void ensureColumn(String table, String column, String ddl) throws SQLException {
        String sql = "PRAGMA table_info(" + table + ")";
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            boolean exists = false;
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try (Statement alt = c.createStatement()) {
                    alt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
                }
            }
        }
    }

    public void upsertLocalBook(LocalLibraryItem item) throws SQLException {
        String sql = """
            INSERT INTO local_library(ebook_id, title, author, package_path, total_pages, status)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(ebook_id) DO UPDATE SET
            title=excluded.title,
            author=excluded.author,
            package_path=excluded.package_path,
            total_pages=excluded.total_pages,
            status=excluded.status
        """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, item.ebookId());
            ps.setString(2, item.title());
            ps.setString(3, item.author());
            ps.setString(4, item.packagePath());
            ps.setInt(5, item.totalPages());
            ps.setString(6, item.status());
            ps.executeUpdate();
        }
    }

    public List<LocalLibraryItem> listLocalLibrary() throws SQLException {
        List<LocalLibraryItem> out = new ArrayList<>();
        String sql = "SELECT ebook_id, title, author, package_path, total_pages, status FROM local_library ORDER BY created_at DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new LocalLibraryItem(
                    rs.getInt("ebook_id"),
                    rs.getString("title"),
                    rs.getString("author"),
                    rs.getString("package_path"),
                    rs.getInt("total_pages"),
                    rs.getString("status")
                ));
            }
        }
        return out;
    }

    public void addBookmark(int ebookId, int pageNo, String label) throws SQLException {
        String sql = "INSERT INTO bookmarks(ebook_id, page_no, label) VALUES(?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.setString(3, label);
            ps.executeUpdate();
        }
    }

    public void addNote(int ebookId, int pageNo, String title, String content) throws SQLException {
        String sql = "INSERT INTO notes(ebook_id, page_no, title, content) VALUES(?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.setString(3, title);
            ps.setString(4, content);
            ps.executeUpdate();
        }
    }

    public void addHighlight(int ebookId, int pageNo, String textBlock) throws SQLException {
        String sql = "INSERT INTO highlights(ebook_id, page_no, text_block) VALUES(?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.setString(3, textBlock);
            ps.executeUpdate();
        }
    }

    public void addHighlight(int ebookId, int pageNo, String textBlock, String bboxJson, String color) throws SQLException {
        String sql = "INSERT INTO highlights(ebook_id, page_no, text_block, bbox_json, color) VALUES(?,?,?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.setString(3, textBlock);
            ps.setString(4, bboxJson);
            ps.setString(5, color);
            ps.executeUpdate();
        }
    }

    public List<HighlightItem> listHighlights(int ebookId, int pageNo) throws SQLException {
        List<HighlightItem> out = new ArrayList<>();
        String sql = "SELECT id, ebook_id, page_no, text_block, bbox_json, color FROM highlights WHERE ebook_id=? AND page_no=? ORDER BY id ASC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HighlightItem(
                        rs.getInt("id"),
                        rs.getInt("ebook_id"),
                        rs.getInt("page_no"),
                        rs.getString("text_block"),
                        rs.getString("bbox_json"),
                        rs.getString("color")
                    ));
                }
            }
        }
        return out;
    }

    public List<HighlightItem> listHighlightsByBook(int ebookId) throws SQLException {
        List<HighlightItem> out = new ArrayList<>();
        String sql = "SELECT id, ebook_id, page_no, text_block, bbox_json, color FROM highlights WHERE ebook_id=? ORDER BY id DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HighlightItem(
                        rs.getInt("id"),
                        rs.getInt("ebook_id"),
                        rs.getInt("page_no"),
                        rs.getString("text_block"),
                        rs.getString("bbox_json"),
                        rs.getString("color")
                    ));
                }
            }
        }
        return out;
    }

    public void deleteHighlightById(int id) throws SQLException {
        String sql = "DELETE FROM highlights WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public List<HighlightPageItem> listHighlightPages(int ebookId) throws SQLException {
        List<HighlightPageItem> out = new ArrayList<>();
        String sql = "SELECT page_no, COUNT(*) AS cnt FROM highlights WHERE ebook_id=? GROUP BY page_no ORDER BY page_no ASC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HighlightPageItem(rs.getInt("page_no"), rs.getInt("cnt")));
                }
            }
        }
        return out;
    }

    public void clearHighlights(int ebookId, int pageNo) throws SQLException {
        String sql = "DELETE FROM highlights WHERE ebook_id=? AND page_no=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.executeUpdate();
        }
    }

    public void addAnnotation(int ebookId, int pageNo, String strokeJson) throws SQLException {
        String sql = "INSERT INTO annotations(ebook_id, page_no, stroke_json) VALUES(?,?,?)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.setString(3, strokeJson);
            ps.executeUpdate();
        }
    }

    public List<String> listAnnotations(int ebookId, int pageNo) throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = "SELECT stroke_json FROM annotations WHERE ebook_id=? AND page_no=? ORDER BY id ASC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("stroke_json"));
                }
            }
        }
        return out;
    }

    public void deleteLastAnnotation(int ebookId, int pageNo) throws SQLException {
        String sql = "DELETE FROM annotations WHERE id=(SELECT id FROM annotations WHERE ebook_id=? AND page_no=? ORDER BY id DESC LIMIT 1)";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.executeUpdate();
        }
    }

    public void clearAnnotations(int ebookId, int pageNo) throws SQLException {
        String sql = "DELETE FROM annotations WHERE ebook_id=? AND page_no=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, pageNo);
            ps.executeUpdate();
        }
    }

    public List<AnnotationItem> listAnnotationItems(int ebookId) throws SQLException {
        List<AnnotationItem> out = new ArrayList<>();
        String sql = "SELECT id, ebook_id, page_no, stroke_json, created_at FROM annotations WHERE ebook_id=? ORDER BY id DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AnnotationItem(
                        rs.getInt("id"),
                        rs.getInt("ebook_id"),
                        rs.getInt("page_no"),
                        rs.getString("stroke_json"),
                        rs.getString("created_at")
                    ));
                }
            }
        }
        return out;
    }

    public void deleteAnnotationById(int id) throws SQLException {
        String sql = "DELETE FROM annotations WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public List<AnnotationPageItem> listAnnotationPages(int ebookId) throws SQLException {
        List<AnnotationPageItem> out = new ArrayList<>();
        String sql = "SELECT page_no, COUNT(*) AS cnt, MAX(created_at) AS latest_at FROM annotations WHERE ebook_id=? GROUP BY page_no ORDER BY page_no ASC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AnnotationPageItem(
                        rs.getInt("page_no"),
                        rs.getInt("cnt"),
                        rs.getString("latest_at")
                    ));
                }
            }
        }
        return out;
    }

    public void upsertProgress(int ebookId, int currentPage) throws SQLException {
        String sql = """
            INSERT INTO reading_progress(ebook_id, current_page)
            VALUES(?,?)
            ON CONFLICT(ebook_id) DO UPDATE SET
            current_page=excluded.current_page,
            updated_at=CURRENT_TIMESTAMP
        """;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            ps.setInt(2, currentPage);
            ps.executeUpdate();
        }
    }

    public List<ReadingProgressItem> listReadingProgress() throws SQLException {
        List<ReadingProgressItem> out = new ArrayList<>();
        String sql = "SELECT ebook_id, current_page, updated_at FROM reading_progress ORDER BY updated_at DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ReadingProgressItem(
                    rs.getInt("ebook_id"),
                    rs.getInt("current_page"),
                    rs.getString("updated_at")
                ));
            }
        }
        return out;
    }

    public List<BookmarkItem> listBookmarks(int ebookId) throws SQLException {
        List<BookmarkItem> out = new ArrayList<>();
        String sql = "SELECT id, ebook_id, page_no, label FROM bookmarks WHERE ebook_id=? ORDER BY created_at DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BookmarkItem(
                        rs.getInt("id"),
                        rs.getInt("ebook_id"),
                        rs.getInt("page_no"),
                        rs.getString("label")
                    ));
                }
            }
        }
        return out;
    }

    public void renameBookmark(int bookmarkId, String newLabel) throws SQLException {
        String sql = "UPDATE bookmarks SET label=? WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newLabel);
            ps.setInt(2, bookmarkId);
            ps.executeUpdate();
        }
    }

    public void deleteBookmark(int bookmarkId) throws SQLException {
        String sql = "DELETE FROM bookmarks WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, bookmarkId);
            ps.executeUpdate();
        }
    }

    public List<NoteItem> listNotes(int ebookId) throws SQLException {
        List<NoteItem> out = new ArrayList<>();
        String sql = "SELECT id, ebook_id, page_no, title, content, COALESCE(updated_at, created_at) AS updated_at FROM notes WHERE ebook_id=? ORDER BY COALESCE(updated_at, created_at) DESC";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, ebookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NoteItem(
                        rs.getInt("id"),
                        rs.getInt("ebook_id"),
                        rs.getInt("page_no"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("updated_at")
                    ));
                }
            }
        }
        return out;
    }

    public void updateNote(int noteId, String title, String content) throws SQLException {
        String sql = "UPDATE notes SET title=?, content=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, content);
            ps.setInt(3, noteId);
            ps.executeUpdate();
        }
    }

    public void deleteNote(int noteId) throws SQLException {
        String sql = "DELETE FROM notes WHERE id=?";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, noteId);
            ps.executeUpdate();
        }
    }
}
