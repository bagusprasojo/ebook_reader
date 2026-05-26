package com.ebook.reader.ui;

import com.ebook.reader.api.BackendApiClient;
import com.ebook.reader.api.PackageDownloader;
import com.ebook.reader.config.AppConfig;
import com.ebook.reader.config.ConfigLoader;
import com.ebook.reader.crypto.DeviceKeyStore;
import com.ebook.reader.crypto.LocalBookReader;
import com.ebook.reader.crypto.PackageCryptoService;
import com.ebook.reader.model.*;
import com.ebook.reader.storage.SQLiteStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.geometry.BoundingBox;
import javafx.scene.Parent;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.Cursor;
import javafx.scene.effect.PerspectiveTransform;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Shear;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.*;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.Color;
import javafx.scene.input.ScrollEvent;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public class MainWindow {
    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());
    private AppConfig config;
    private BackendApiClient api;
    private PackageDownloader downloader;
    private SessionTokens tokens;
    private String loggedInEmail = "offline-user";
    private final Path appDataRoot = Paths.get(System.getProperty("user.home"), ".ebook-reader");
    private final Path packagesRoot = appDataRoot.resolve("packages");
    private final DeviceKeyStore deviceKeyStore = new DeviceKeyStore(appDataRoot);
    private final SQLiteStore store = new SQLiteStore(appDataRoot.resolve("reader.db"));
    private final Label status = new Label("Ready");
    private final TabPane mdiTabs = new TabPane();
    private final TextField continueSearch = new TextField();
    private final TextField librarySearch = new TextField();
    private final FlowPane continueGrid = new FlowPane();
    private final FlowPane libraryGrid = new FlowPane();
    private final Label continueSummary = new Label("0 books");
    private final Label librarySummary = new Label("0 books");
    private final List<LocalLibraryItem> homeBooks = new ArrayList<>();
    private final Map<Integer, Image> homeCoverCache = new HashMap<>();
    private final Map<Integer, Tab> openedReaderTabs = new HashMap<>();

    public Parent build() {
        reloadConfigClients();
        try {
            Files.createDirectories(packagesRoot);
            store.init();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Storage init failed", e);
            status.setText("Storage init failed: " + e.getMessage());
        }
        BorderPane root = new BorderPane();
        root.setTop(buildMenuBar());
        root.setCenter(mdiTabs);
        root.setBottom(status);
        Tab koleksiTab = new Tab("Koleksiku", buildCollectionPane());
        koleksiTab.setClosable(false);
        mdiTabs.getTabs().add(koleksiTab);
        refreshCollection();
        return root;
    }

    private void reloadConfigClients() {
        this.config = ConfigLoader.load();
        this.api = new BackendApiClient(config);
        this.downloader = new PackageDownloader(api);
    }

    private MenuBar buildMenuBar() {
        Menu file = new Menu("File");
        MenuItem login = new MenuItem("Login");
        login.setOnAction(e -> showLoginDialog());
        MenuItem koleksiku = new MenuItem("Koleksiku");
        koleksiku.setOnAction(e -> mdiTabs.getSelectionModel().select(0));
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> System.exit(0));
        file.getItems().addAll(login, koleksiku, new SeparatorMenuItem(), exit);
        Menu setting = new Menu("Setting");
        MenuItem configItem = new MenuItem("Config");
        configItem.setOnAction(e -> showConfigDialog());
        setting.getItems().add(configItem);
        Menu help = new Menu("Help");
        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> showAboutDialog());
        help.getItems().add(about);
        return new MenuBar(file, setting, help);
    }

    private Parent buildCollectionPane() {
        Button downloadBtn = new Button("Download Ebook");
        downloadBtn.setOnAction(e -> showOnlineDownloadDialog());
        Button refreshBtn = new Button("Refresh Koleksi");
        refreshBtn.setOnAction(e -> refreshCollection());

        HBox topBar = new HBox(8, downloadBtn, refreshBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        continueSearch.setPromptText("Search continue reading...");
        continueSearch.textProperty().addListener((obs, oldVal, newVal) -> renderHome());
        librarySearch.setPromptText("Search downloaded library...");
        librarySearch.textProperty().addListener((obs, oldVal, newVal) -> renderHome());
        continueGrid.setHgap(12);
        continueGrid.setVgap(12);
        continueGrid.setPadding(new Insets(4, 0, 8, 0));
        libraryGrid.setHgap(12);
        libraryGrid.setVgap(12);
        libraryGrid.setPadding(new Insets(4, 0, 8, 0));
        ScrollPane continueScroll = new ScrollPane(continueGrid);
        continueScroll.setFitToWidth(true);
        continueScroll.setPannable(true);
        VBox.setVgrow(continueScroll, Priority.ALWAYS);
        ScrollPane gridScroll = new ScrollPane(libraryGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setPannable(true);
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        HBox continueBar = new HBox(8, continueSearch, continueSummary);
        HBox.setHgrow(continueSearch, Priority.ALWAYS);
        VBox continueTabContent = new VBox(8, continueBar, continueScroll);
        continueTabContent.setPadding(new Insets(10, 0, 0, 0));

        HBox libraryBar = new HBox(8, librarySearch, librarySummary);
        HBox.setHgrow(librarySearch, Priority.ALWAYS);
        VBox libraryTabContent = new VBox(8, libraryBar, gridScroll);
        libraryTabContent.setPadding(new Insets(10, 0, 0, 0));

        TabPane homeTabs = new TabPane();
        Tab continueTab = new Tab("Continue Reading", continueTabContent);
        continueTab.setClosable(false);
        Tab libraryTab = new Tab("Downloaded Library", libraryTabContent);
        libraryTab.setClosable(false);
        homeTabs.getTabs().setAll(continueTab, libraryTab);

        VBox content = new VBox(8, topBar, homeTabs);
        content.setPadding(new Insets(12));
        content.setStyle("-fx-background-color: linear-gradient(to bottom, #f7f4ee, #ffffff);");
        return content;
    }

        private void showLoginDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Login");
        ButtonType ok = new ButtonType("Login", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        TextField email = new TextField();
        PasswordField pass = new PasswordField();
        d.getDialogPane().setContent(new VBox(8, new Label("Email"), email, new Label("Password"), pass));
            if (d.showAndWait().orElse(ButtonType.CANCEL) == ok) {
                try {
                    LOG.info("Login start email=" + email.getText().trim());
                    tokens = api.login(email.getText().trim(), pass.getText());
                    loggedInEmail = email.getText().trim();
                    int deviceId = resolveDeviceId(tokens.access());
                    LOG.info("Login success deviceId=" + deviceId);
                    status.setText("Login success. Device active: " + deviceId);
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Login failed", ex);
                    status.setText("Login failed: " + ex.getMessage());
                }
            }
        }

        private void showOnlineDownloadDialog() {
            if (tokens == null) {
                LOG.warning("Download blocked: user not logged in");
                status.setText("Silakan login dulu via File > Login");
                return;
            }
            try {
            List<Book> books = api.listBooks(tokens.access());
            Map<String, Book> byLabel = new HashMap<>();
            for (Book b : books) byLabel.put(b.id() + " - " + b.title(), b);
            List<String> labels = byLabel.keySet().stream().sorted().toList();
            ChoiceDialog<String> d = new ChoiceDialog<>(labels.isEmpty() ? null : labels.get(0), labels);
            d.setTitle("Download Ebook");
            Optional<String> picked = d.showAndWait();
            if (picked.isEmpty()) return;
            Book b = byLabel.get(picked.get());
            int deviceId = resolveDeviceId(tokens.access());
            Path target = packagesRoot.resolve("ebook_" + b.id() + ".bookpkg");
            LOG.info("Download start ebookId=" + b.id() + " deviceId=" + deviceId + " target=" + target);
            downloader.download(tokens.access(), b.id(), deviceId, target);
            store.upsertLocalBook(new LocalLibraryItem(b.id(), b.title(), b.author(), target.toString(), b.totalPages(), "downloaded"));
            LOG.info("Download success ebookId=" + b.id());
            refreshCollection();
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Download failed", ex);
            status.setText("Download gagal: " + ex.getMessage());
        }
    }

    private int resolveDeviceId(String accessToken) throws Exception {
        String deviceHash = computeDeviceHash();
        String publicKeyPem = deviceKeyStore.publicKeyPem();
        for (DeviceInfo d : api.listDevices(accessToken)) {
            if (deviceHash.equals(d.deviceHash()) && publicKeyPem.equals(d.publicKeyPem())) return d.id();
        }
        DeviceInfo created = api.registerDevice(accessToken, deviceHash, System.getProperty("user.name"), System.getProperty("os.name"), "0.1.0", publicKeyPem);
        return created.id();
    }

    private String computeDeviceHash() throws Exception {
        String raw = System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", "") + "|" + System.getenv().getOrDefault("COMPUTERNAME", "") + "|" + System.getProperty("user.name", "");
        byte[] out = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void openReaderTab(LocalLibraryItem item) {
        openReaderTab(item, getLastReadPage(item.ebookId()));
    }

    private void openReaderTab(LocalLibraryItem item, int startPage) {
        try {
            LOG.info("Open reader request ebookId=" + item.ebookId() + " title=" + item.title());
            Tab existing = openedReaderTabs.get(item.ebookId());
            if (existing != null) {
                LOG.info("Open reader reused existing tab ebookId=" + item.ebookId());
                mdiTabs.getSelectionModel().select(existing);
                return;
            }
            LocalBookReader reader = new LocalBookReader(Path.of(item.packagePath()), legacyMasterKeyOrNull(), computeDeviceHash(), deviceKeyStore.privateKey());
            reader.verify(new PackageCryptoService(), PackageCryptoService.readResourceText("/keys/public_key.pem"));
            LOG.info("Open reader verify success ebookId=" + item.ebookId());
            ReaderPane pane = new ReaderPane(item, reader, status, store, startPage, watermarkIdentity());
            Tab tab = new Tab("Baca: " + item.title(), pane.build());
            tab.setOnClosed(e -> openedReaderTabs.remove(item.ebookId()));
            openedReaderTabs.put(item.ebookId(), tab);
            mdiTabs.getTabs().add(tab);
            mdiTabs.getSelectionModel().select(tab);
            LOG.info("Open reader success ebookId=" + item.ebookId());
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Open reader failed ebookId=" + item.ebookId(), ex);
            status.setText("Gagal membuka ebook: " + ex.getMessage());
        }
    }

    private void refreshCollection() {
        try {
            homeBooks.clear();
            homeBooks.addAll(store.listLocalLibrary());
            homeCoverCache.clear();
            renderHome();
        } catch (Exception ex) {
            status.setText("Refresh koleksi gagal: " + ex.getMessage());
        }
    }

    private String[] watermarkIdentity() {
        String userPart = (loggedInEmail == null || loggedInEmail.isBlank()) ? "offline-user" : loggedInEmail;
        try {
            String hash = computeDeviceHash();
            String shortHash = hash.length() > 10 ? hash.substring(0, 10) : hash;
            return new String[] { userPart, "device " + shortHash };
        } catch (Exception ex) {
            return new String[] { userPart, "device unknown" };
        }
    }

    private void renderHome() {
        try {
            Map<Integer, ReadingProgressItem> progressByBook = new HashMap<>();
            for (ReadingProgressItem p : store.listReadingProgress()) progressByBook.put(p.ebookId(), p);

            String cq = continueSearch.getText() == null ? "" : continueSearch.getText().trim().toLowerCase();
            String lq = librarySearch.getText() == null ? "" : librarySearch.getText().trim().toLowerCase();

            continueGrid.getChildren().clear();
            List<ReadingProgressItem> sortedProgress = new ArrayList<>(progressByBook.values());
            sortedProgress.sort(Comparator.comparing(ReadingProgressItem::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            int added = 0;
            for (ReadingProgressItem p : sortedProgress) {
                LocalLibraryItem b = homeBooks.stream().filter(it -> it.ebookId() == p.ebookId()).findFirst().orElse(null);
                if (b == null) continue;
                String t = b.title() == null ? "" : b.title().toLowerCase();
                String a = b.author() == null ? "" : b.author().toLowerCase();
                if (!cq.isEmpty() && !t.contains(cq) && !a.contains(cq)) continue;
                continueGrid.getChildren().add(createBookCard(b, p.currentPage(), true));
                added++;
            }
            if (added == 0) {
                Label empty = new Label("Belum ada progress baca. Buka buku dari library di bawah.");
                empty.setStyle("-fx-text-fill:#666;");
                continueGrid.getChildren().add(empty);
            }
            continueSummary.setText(added + " books");

            libraryGrid.getChildren().clear();
            int lcount = 0;
            for (LocalLibraryItem b : homeBooks) {
                String t = b.title() == null ? "" : b.title().toLowerCase();
                String a = b.author() == null ? "" : b.author().toLowerCase();
                if (!lq.isEmpty() && !t.contains(lq) && !a.contains(lq)) continue;
                ReadingProgressItem p = progressByBook.get(b.ebookId());
                libraryGrid.getChildren().add(createBookCard(b, p == null ? 0 : p.currentPage(), false));
                lcount++;
            }
            librarySummary.setText(lcount + " books");
        } catch (Exception ex) {
            status.setText("Render home gagal: " + ex.getMessage());
        }
    }

    private VBox createBookCard(LocalLibraryItem b, int currentPage, boolean compact) {
        double cw = 120;
        double ch = 160;
        StackPane coverPane = new StackPane();
        coverPane.setMinSize(cw, ch);
        coverPane.setPrefSize(cw, ch);
        coverPane.setMaxSize(cw, ch);
        coverPane.setStyle("-fx-background-color:#f0e8d8;-fx-background-radius:8px;");
        coverPane.setAlignment(Pos.TOP_CENTER);
        Image coverImage = resolveCoverImage(b);
        if (coverImage != null) {
            ImageView iv = new ImageView(coverImage);
            iv.setPreserveRatio(true);
            iv.setFitWidth(cw);
            iv.setFitHeight(ch);
            coverPane.getChildren().add(iv);
        } else {
            Label fallback = new Label((b.title() == null || b.title().isBlank()) ? "BOOK" : b.title().substring(0, Math.min(1, b.title().length())).toUpperCase());
            fallback.setAlignment(Pos.CENTER);
            fallback.setStyle("-fx-text-fill:#5f4b32;-fx-font-size:28px;-fx-font-weight:bold;");
            coverPane.getChildren().add(fallback);
        }

        Label title = new Label(b.title());
        title.setWrapText(true);
        title.setStyle("-fx-font-weight:bold;");
        Label author = new Label((b.author() == null || b.author().isBlank()) ? "-" : b.author());
        author.setStyle("-fx-text-fill:#666;");
        String progressText = currentPage > 0 ? ("Page " + currentPage + "/" + b.totalPages()) : "Belum mulai";
        Label progress = new Label(progressText);
        progress.setStyle("-fx-text-fill:#3b6a5a;");

        HBox coverRow = new HBox(coverPane);
        coverRow.setAlignment(Pos.TOP_CENTER);
        coverRow.setFillHeight(false);

        VBox card = new VBox(6, coverRow, title, author, progress);
        card.setPadding(new Insets(8));
        card.setPrefWidth(180);
        card.setStyle("-fx-background-color:white;-fx-border-color:#e8e2d8;-fx-border-radius:10;-fx-background-radius:10;");
        card.setOnMouseClicked(ev -> {
            if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() >= 1) {
                openReaderTab(b, Math.max(1, currentPage));
            }
        });
        return card;
    }

    private int getLastReadPage(int ebookId) {
        try {
            for (ReadingProgressItem p : store.listReadingProgress()) {
                if (p.ebookId() == ebookId) return Math.max(1, p.currentPage());
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private Image resolveCoverImage(LocalLibraryItem b) {
        Image cached = homeCoverCache.get(b.ebookId());
        if (cached != null) return cached;
        try {
            try (ZipFile zf = new ZipFile(Path.of(b.packagePath()).toFile())) {
                byte[] thumb = PackageCryptoService.readZipEntry(zf, "thumb/cover.png");
                Image img = new Image(new ByteArrayInputStream(thumb));
                if (!img.isError()) {
                    homeCoverCache.put(b.ebookId(), img);
                    return img;
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Cover thumbnail load failed ebookId=" + b.ebookId(), ex);
        }
        try {
            LocalBookReader lr = new LocalBookReader(Path.of(b.packagePath()), legacyMasterKeyOrNull(), computeDeviceHash(), deviceKeyStore.privateKey());
            Image img = lr.renderPageImage(1);
            if (img != null) {
                homeCoverCache.put(b.ebookId(), img);
            }
            return img;
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Cover load failed ebookId=" + b.ebookId(), ex);
            return null;
        }
    }

    private byte[] legacyMasterKeyOrNull() {
        String keyB64 = config.masterKeyB64();
        if (keyB64 == null || keyB64.isBlank()) keyB64 = System.getenv("READER_MASTER_KEY_B64");
        if (keyB64 == null || keyB64.isBlank()) return null;
        return Base64.getDecoder().decode(keyB64);
    }

    private void showConfigDialog() {
        Alert a = new Alert(Alert.AlertType.INFORMATION, "Saved to: " + ConfigLoader.userConfigPath());
        a.showAndWait();
    }

    private void showAboutDialog() {
        Alert a = new Alert(Alert.AlertType.INFORMATION, "Ebook Reader JavaFX 0.1.0");
        a.showAndWait();
    }

    private static final class ReaderPane {
        private final LocalLibraryItem item;
        private final LocalBookReader reader;
        private final Label status;
        private final SQLiteStore store;
        private final String[] watermarkIdentity;
        private int currentPage = 1;
        private boolean spreadMode = true;
        private final ImageView left = new ImageView();
        private final ImageView right = new ImageView();
        private final HBox pages = new HBox(8, left, right);
        private final Canvas annotation = new Canvas(980, 620);
        private final Canvas watermark = new Canvas(980, 620);
        private StackPane pageLayer;
        private final Pane transitionOverlay = new Pane();
        private ScrollPane pageScroll;
        private final Scale zoomTransform = new Scale(1, 1, 0, 0);
        private final Pane highlightOverlay = new Pane();
        private final Pane searchOverlay = new Pane();
        private final List<List<Double>> currentStroke = new ArrayList<>();
        private final ListView<BookmarkItem> bookmarkList = new ListView<>();
        private final ListView<NoteItem> noteList = new ListView<>();
        private final ListView<TocItem> tocList = new ListView<>();
        private final ListView<AnnotationPageItem> annotationList = new ListView<>();
        private final ListView<HighlightPageItem> highlightList = new ListView<>();
        private final TextField noteFilter = new TextField();
        private final Label notePreviewTitle = new Label("-");
        private final Label notePreviewMeta = new Label("-");
        private final TextArea notePreviewContent = new TextArea();
        private final List<NoteItem> allNotes = new ArrayList<>();
        private final ToggleButton annotationMode = new ToggleButton();
        private final ToggleButton highlightMode = new ToggleButton();
        private final ToggleButton highlightToolsToggle = new ToggleButton();
        private final ToggleButton annotationToolsToggle = new ToggleButton();
        private final ColorPicker annotationColor = new ColorPicker(Color.RED);
        private final ColorPicker highlightColor = new ColorPicker(Color.YELLOW);
        private final Slider annotationThickness = new Slider(1.0, 8.0, 2.0);
        private final Label zoomLabel = new Label("100%");
        private Button firstBtn;
        private Button prevBtn;
        private Button nextBtn;
        private Button lastBtn;
        private final TextField pageJump = new TextField("1");
        private final Label searchResultLabel = new Label("0 / 0");
        private final List<SearchResult> searchResults = new ArrayList<>();
        private int searchResultIndex = -1;
        private double zoomScale = 1.0;
        private static final double ZOOM_MIN = 1.0;
        private static final double ZOOM_MAX = 3.0;
        private static final double ZOOM_STEP = 0.1;
        private boolean turnAnimating = false;
        private final Rectangle selectionRect = new Rectangle();
        private double selStartX = -1;
        private double selStartY = -1;
        private int currentStrokePage = -1;

        private ReaderPane(LocalLibraryItem item, LocalBookReader reader, Label status, SQLiteStore store, int startPage, String[] watermarkIdentity) {
            this.item = item;
            this.reader = reader;
            this.status = status;
            this.store = store;
            this.watermarkIdentity = watermarkIdentity;
            this.currentPage = Math.max(1, startPage);
        }

        private Parent build() {
            left.setPreserveRatio(true); right.setPreserveRatio(true); left.setFitWidth(470); right.setFitWidth(470);
            pages.setAlignment(Pos.CENTER);
            highlightOverlay.setPickOnBounds(false);
            searchOverlay.setPickOnBounds(false);
            searchOverlay.setMouseTransparent(true);
            watermark.setManaged(false);
            watermark.setMouseTransparent(true);
            annotation.setManaged(false);
            transitionOverlay.setPickOnBounds(false);
            transitionOverlay.setMouseTransparent(true);
            transitionOverlay.setManaged(false);
            selectionRect.setFill(Color.color(1, 1, 0, 0.15));
            selectionRect.setStroke(Color.ORANGE);
            selectionRect.setManaged(false);
            selectionRect.setMouseTransparent(true);
            selectionRect.setVisible(false);
            pageLayer = new StackPane(pages, highlightOverlay, searchOverlay, watermark, annotation, selectionRect, transitionOverlay);
            pageLayer.getTransforms().add(zoomTransform);
            pageLayer.setAlignment(Pos.TOP_CENTER);
            StackPane.setAlignment(pages, Pos.TOP_CENTER);
            StackPane.setAlignment(highlightOverlay, Pos.TOP_LEFT);
            StackPane.setAlignment(searchOverlay, Pos.TOP_LEFT);
            StackPane.setAlignment(watermark, Pos.TOP_LEFT);
            StackPane.setAlignment(annotation, Pos.TOP_LEFT);
            StackPane.setAlignment(selectionRect, Pos.TOP_LEFT);
            StackPane.setAlignment(transitionOverlay, Pos.TOP_LEFT);
            pageScroll = new ScrollPane(new Group(pageLayer));
            pageScroll.setPannable(true);
            pageScroll.setFitToWidth(false);
            pageScroll.setFitToHeight(false);
            pageScroll.setMinWidth(0);
            pageScroll.viewportBoundsProperty().addListener((obs, oldV, newV) -> applyZoom());
            pageScroll.addEventFilter(ScrollEvent.SCROLL, e -> {
                if (e.isControlDown()) {
                    if (e.getDeltaY() > 0) zoomIn();
                    if (e.getDeltaY() < 0) zoomOut();
                    e.consume();
                }
            });
            GraphicsContext gc = annotation.getGraphicsContext2D();
            gc.setStroke(Color.RED);
            gc.setLineWidth(2.0);
            annotation.setCursor(Cursor.DEFAULT);
            annotationMode.selectedProperty().addListener((obs, oldVal, on) -> {
                annotation.setCursor(on ? Cursor.CROSSHAIR : Cursor.DEFAULT);
                if (on) {
                    highlightMode.setSelected(false);
                }
                updatePanState();
            });
            highlightMode.selectedProperty().addListener((obs, oldVal, on) -> {
                if (on) {
                    annotationMode.setSelected(false);
                    annotation.setCursor(Cursor.CROSSHAIR);
                } else if (!annotationMode.isSelected()) {
                    annotation.setCursor(Cursor.DEFAULT);
                }
                updatePanState();
            });
            highlightToolsToggle.selectedProperty().addListener((obs, oldVal, on) -> {
                if (on) {
                    annotationToolsToggle.setSelected(false);
                }
            });
            annotationToolsToggle.selectedProperty().addListener((obs, oldVal, on) -> {
                if (on) {
                    highlightToolsToggle.setSelected(false);
                }
            });
            annotationColor.setOnAction(e -> gc.setStroke(annotationColor.getValue()));
            annotationThickness.valueProperty().addListener((obs, oldVal, newVal) -> gc.setLineWidth(newVal.doubleValue()));

            annotation.setOnMousePressed(e -> {
                if (highlightMode.isSelected()) {
                    Point2D start = pageLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
                    selStartX = start.getX();
                    selStartY = start.getY();
                    selectionRect.setX(selStartX);
                    selectionRect.setY(selStartY);
                    selectionRect.setWidth(0);
                    selectionRect.setHeight(0);
                    selectionRect.setVisible(true);
                    return;
                }
                if (!annotationMode.isSelected()) return;
                if (e.getButton() == MouseButton.PRIMARY) {
                    Point2D p = pageLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
                    currentStrokePage = pageAtPoint(p.getX(), p.getY());
                    if (currentStrokePage < 1) return;
                    currentStroke.clear();
                    gc.setStroke(annotationColor.getValue());
                    gc.setLineWidth(annotationThickness.getValue());
                    gc.beginPath();
                    gc.moveTo(p.getX(), p.getY());
                    gc.stroke();
                    currentStroke.add(List.of(p.getX(), p.getY()));
                }
            });
            annotation.setOnMouseDragged(e -> {
                if (highlightMode.isSelected()) {
                    Point2D cur = pageLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
                    double curX = cur.getX();
                    double curY = cur.getY();
                    selectionRect.setX(Math.min(selStartX, curX));
                    selectionRect.setY(Math.min(selStartY, curY));
                    selectionRect.setWidth(Math.abs(curX - selStartX));
                    selectionRect.setHeight(Math.abs(curY - selStartY));
                    return;
                }
                if (!annotationMode.isSelected()) return;
                if (e.getButton() == MouseButton.PRIMARY) {
                    Point2D p = pageLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
                    gc.lineTo(p.getX(), p.getY());
                    gc.stroke();
                    currentStroke.add(List.of(p.getX(), p.getY()));
                }
            });
            annotation.setOnMouseReleased(e -> {
                if (highlightMode.isSelected()) {
                    handleHighlightSelection(pageLayer);
                    selectionRect.setVisible(false);
                    return;
                }
                if (!annotationMode.isSelected()) return;
                if (e.getButton() == MouseButton.PRIMARY && !currentStroke.isEmpty()) {
                    persistCurrentStroke();
                }
            });
            firstBtn = new Button("First"); firstBtn.setOnAction(e -> jumpToPage(1));
            prevBtn = new Button("Prev"); prevBtn.setOnAction(e -> turnPageAnimated(false));
            nextBtn = new Button("Next"); nextBtn.setOnAction(e -> turnPageAnimated(true));
            lastBtn = new Button("Last"); lastBtn.setOnAction(e -> jumpToLastPage());
            pageJump.setPrefColumnCount(4);
            pageJump.setMaxWidth(64);
            pageJump.setOnAction(e -> jumpToPageFromInput());
            Button zoomOutBtn = new Button("-"); zoomOutBtn.setOnAction(e -> zoomOut());
            Button zoomInBtn = new Button("+"); zoomInBtn.setOnAction(e -> zoomIn());
            ToggleButton singlePage = new ToggleButton("Single Page");
            singlePage.setSelected(!spreadMode);
            singlePage.setOnAction(e -> {
                spreadMode = !singlePage.isSelected();
                zoomScale = 1.0;
                if (spreadMode) {
                    currentPage = normalizePageForSpread(currentPage);
                }
                LOG.info("Reader page mode=" + (spreadMode ? "spread" : "single"));
                render();
            });
            TextField search = new TextField(); search.setPromptText("Search text in this book");
            Button find = new Button("Search"); find.setOnAction(e -> performSearch(search.getText()));
            Button prevResult = new Button("Prev Result"); prevResult.setOnAction(e -> moveSearchResult(-1));
            Button nextResult = new Button("Next Result"); nextResult.setOnAction(e -> moveSearchResult(1));
            Button bookmark = new Button("Bookmark"); bookmark.setOnAction(e -> addBookmark());
            Button note = new Button("Note"); note.setOnAction(e -> addNote());
            ToggleButton annotationBtn = new ToggleButton("Annotation");
            ToggleButton highlight = new ToggleButton("Highlight");
            highlight.setOnAction(e -> {
                boolean on = highlight.isSelected();
                highlightMode.setSelected(on);
                highlightToolsToggle.setSelected(on);
                LOG.info("Highlight mode=" + on);
                if (on) {
                    annotationBtn.setSelected(false);
                    annotationMode.setSelected(false);
                    annotationToolsToggle.setSelected(false);
                }
            });
            annotationBtn.setOnAction(e -> {
                boolean on = annotationBtn.isSelected();
                annotationMode.setSelected(on);
                annotationToolsToggle.setSelected(on);
                LOG.info("Annotation mode=" + on);
                if (on) {
                    highlight.setSelected(false);
                    highlightMode.setSelected(false);
                    highlightToolsToggle.setSelected(false);
                }
            });
            Button undoAnno = new Button("Undo Annotation");
            undoAnno.setOnAction(e -> undoAnnotation());
            Button clearAnno = new Button("Clear Page Annotation");
            clearAnno.setOnAction(e -> clearAnnotation());
            annotationThickness.setShowTickLabels(false);
            annotationThickness.setPrefWidth(90);

            HBox hiTools = new HBox(4, new Label("Color"), highlightColor);
            hiTools.visibleProperty().bind(highlightToolsToggle.selectedProperty());
            hiTools.managedProperty().bind(highlightToolsToggle.selectedProperty());

            HBox annoTools = new HBox(4, new Label("Color"), annotationColor, new Label("W"), annotationThickness, undoAnno, clearAnno);
            annoTools.visibleProperty().bind(annotationToolsToggle.selectedProperty());
            annoTools.managedProperty().bind(annotationToolsToggle.selectedProperty());

            HBox toolbar = new HBox(6,
                firstBtn, prevBtn, pageJump, nextBtn, lastBtn,
                singlePage, new Label("Zoom"), zoomOutBtn, zoomInBtn, zoomLabel,
                bookmark, note,
                highlight, hiTools,
                annotationBtn, annoTools,
                new Label("Search"), search, find, prevResult, nextResult, searchResultLabel
            );
            toolbar.setAlignment(Pos.CENTER_LEFT);
            search.setPrefWidth(180);
            VBox controls = new VBox(4, toolbar);

            bookmarkList.setCellFactory(ignored -> new ListCell<>() { @Override protected void updateItem(BookmarkItem bm, boolean empty) { super.updateItem(bm, empty); setText(empty || bm == null ? "" : "p" + bm.pageNo() + " - " + bm.label()); }});
            bookmarkList.setContextMenu(buildBookmarkContextMenu());
            bookmarkList.setOnMouseClicked(ev -> { if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) { BookmarkItem bm = bookmarkList.getSelectionModel().getSelectedItem(); if (bm != null) { currentPage = bm.pageNo(); render(); } }});
            noteList.setCellFactory(ignored -> new ListCell<>() { @Override protected void updateItem(NoteItem n, boolean empty) { super.updateItem(n, empty); if (empty || n == null) setText(""); else { String t = (n.title() == null || n.title().isBlank()) ? "(No Title)" : n.title(); String p = n.content().length() > 80 ? n.content().substring(0, 80) + "..." : n.content(); setText("p" + n.pageNo() + " - " + t + "\n" + p); } }});
            noteList.setContextMenu(buildNoteContextMenu());
            noteList.setOnMouseClicked(ev -> { if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) { NoteItem n = noteList.getSelectionModel().getSelectedItem(); if (n != null) { currentPage = n.pageNo(); render(); } }});
            noteList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateNotePreview(newVal));
            noteFilter.setPromptText("Filter notes (title/content/page)");
            noteFilter.textProperty().addListener((obs, oldVal, newVal) -> applyNoteFilter());

            tocList.setCellFactory(ignored -> new ListCell<>() {
                @Override
                protected void updateItem(TocItem toc, boolean empty) {
                    super.updateItem(toc, empty);
                    if (empty || toc == null) {
                        setText("");
                    } else {
                        String indent = "  ".repeat(Math.max(0, toc.level() - 1));
                        setText(indent + "p" + toc.page() + " - " + toc.title());
                    }
                }
            });
            tocList.setOnMouseClicked(ev -> {
                if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                    TocItem toc = tocList.getSelectionModel().getSelectedItem();
                    if (toc != null) jumpToPage(toc.page());
                }
            });
            Tab tocTab = new Tab("TOC", new VBox(6, tocList)); tocTab.setClosable(false);
            Tab bmTab = new Tab("Bookmarks", new VBox(6, bookmarkList)); bmTab.setClosable(false);
            notePreviewContent.setEditable(false);
            notePreviewContent.setWrapText(true);
            notePreviewContent.setPrefRowCount(6);
            VBox notePreview = new VBox(4, new Label("Detail"), notePreviewTitle, notePreviewMeta, notePreviewContent);
            VBox notePanel = new VBox(6, noteFilter, noteList, notePreview);
            VBox.setVgrow(noteList, Priority.ALWAYS);
            Tab ntTab = new Tab("Notes", notePanel); ntTab.setClosable(false);
            highlightList.setCellFactory(ignored -> new ListCell<>() {
                @Override
                protected void updateItem(HighlightPageItem h, boolean empty) {
                    super.updateItem(h, empty);
                    if (empty || h == null) {
                        setText("");
                    } else {
                        setText("p" + h.pageNo() + " (" + h.count() + " highlights)");
                    }
                }
            });
            highlightList.setOnMouseClicked(ev -> {
                if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                    HighlightPageItem h = highlightList.getSelectionModel().getSelectedItem();
                    if (h != null) {
                        currentPage = h.pageNo();
                        render();
                    }
                }
            });
            highlightList.setContextMenu(buildHighlightContextMenu());
            Tab hiTab = new Tab("Highlights", new VBox(6, highlightList)); hiTab.setClosable(false);
            annotationList.setCellFactory(ignored -> new ListCell<>() {
                @Override
                protected void updateItem(AnnotationPageItem a, boolean empty) {
                    super.updateItem(a, empty);
                    if (empty || a == null) {
                        setText("");
                    } else {
                        setText("p" + a.pageNo() + " (" + a.count() + " strokes)");
                    }
                }
            });
            annotationList.setOnMouseClicked(ev -> {
                if (ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2) {
                    AnnotationPageItem a = annotationList.getSelectionModel().getSelectedItem();
                    if (a != null) {
                        currentPage = a.pageNo();
                        render();
                    }
                }
            });
            annotationList.setContextMenu(buildAnnotationContextMenu());
            Tab anTab = new Tab("Annotations", new VBox(6, annotationList)); anTab.setClosable(false);
            TabPane side = new TabPane(tocTab, bmTab, ntTab, hiTab, anTab);
            side.setMinWidth(300);
            side.setPrefWidth(300);
            side.setMaxWidth(300);
            side.setPadding(new Insets(0, 0, 0, 8));
            HBox content = new HBox(8, pageScroll, side); HBox.setHgrow(pageScroll, Priority.ALWAYS);
            VBox root = new VBox(4, controls, content); root.setPadding(new Insets(6));
            reloadToc();
            reloadBookmarks();
            reloadNotes();
            reloadHighlights();
            reloadAnnotations();
            applyZoom();
            updatePanState();
            render();
            return root;
        }

        private void updatePanState() {
            if (pageScroll == null) return;
            // Disable drag-to-pan while user is selecting highlight or drawing annotation.
            pageScroll.setPannable(!(highlightMode.isSelected() || annotationMode.isSelected()));
        }

        private void turnPageAnimated(boolean forward) {
            if (turnAnimating) return;
            int target = targetPage(forward);
            if (target == currentPage) return;
            currentPage = target;
            render();
        }

        private int targetPage(boolean forward) {
            try {
                int total = reader.totalPages();
                if (!spreadMode) {
                    return forward ? Math.min(total, currentPage + 1) : Math.max(1, currentPage - 1);
                }
                if (forward) {
                    if (currentPage >= lastSpreadStart(total)) return currentPage;
                    return Math.min(lastSpreadStart(total), currentPage + 2);
                }
                return Math.max(1, currentPage - 2);
            } catch (Exception ex) {
                int step = spreadMode ? 2 : 1;
                return forward ? currentPage + step : Math.max(1, currentPage - step);
            }
        }

        private int lastSpreadStart(int total) {
            if (total <= 1) return 1;
            return (total % 2 == 0) ? total : total - 1;
        }

        private int normalizePageForSpread(int page) {
            int p = Math.max(1, page);
            if (p <= 1) return 1;
            return (p % 2 == 0) ? p : p - 1;
        }

        private void jumpToPageFromInput() {
            try {
                jumpToPage(Integer.parseInt(pageJump.getText().trim()));
            } catch (NumberFormatException ex) {
                status.setText("Nomor halaman tidak valid");
                updatePageJumpText();
            }
        }

        private void jumpToPage(int page) {
            if (turnAnimating) return;
            try {
                int total = reader.totalPages();
                int normalized = spreadMode ? normalizePageForSpread(page) : Math.max(1, page);
                int max = spreadMode ? lastSpreadStart(total) : total;
                int target = Math.min(max, normalized);
                currentPage = target;
                render();
            } catch (Exception ex) {
                status.setText("Gagal menuju halaman: " + ex.getMessage());
                updatePageJumpText();
            }
        }

        private void jumpToLastPage() {
            try {
                int total = reader.totalPages();
                currentPage = spreadMode ? lastSpreadStart(total) : total;
                render();
            } catch (Exception ex) {
                status.setText("Gagal menuju halaman terakhir: " + ex.getMessage());
            }
        }

        private ImageView halfView(Image img, double x, double w, double h) {
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(false);
            iv.setFitWidth(Math.max(1, w));
            iv.setFitHeight(Math.max(1, h));
            iv.setViewport(new Rectangle2D(Math.max(0, x), 0, Math.max(1, w), Math.max(1, h)));
            return iv;
        }

        private void playCurlTransition(boolean forward, ImageView oldView, ImageView newView) {
            double w = Math.max(300, pageLayer.getWidth());
            double h = Math.max(240, pageLayer.getHeight());
            double half = w / 2.0;
            LOG.info("Half=" + half + " w=" + w + " h=" + h);

            oldView.setVisible(false);
            newView.setVisible(false);

            ImageView oldLeft = halfView(oldView.getImage(), 0, half, h);
            ImageView oldRight = halfView(oldView.getImage(), half, half, h);
            ImageView newLeft = halfView(newView.getImage(), 0, half, h);
            ImageView newRight = halfView(newView.getImage(), half, half, h);
            oldLeft.setLayoutX(0); oldRight.setLayoutX(half);
            newLeft.setLayoutX(0); newRight.setLayoutX(half);
            oldLeft.setLayoutY(0); oldRight.setLayoutY(0);
            newLeft.setLayoutY(0); newRight.setLayoutY(0);

            Rectangle gutterShadow = spineShadow(h);
            Interpolator paperEase = Interpolator.SPLINE(0.18, 0.72, 0.18, 1.0);
            int strips = 18;
            double stripW = half / strips;
            List<Animation> animations = new ArrayList<>();

            double moveX = 0;
            if (forward) {
                newRight.setOpacity(0.0);
                transitionOverlay.getChildren().setAll(newLeft, newRight, oldLeft, gutterShadow);
                for (int i = 0; i < strips; i++) {
                    StackPane strip = pageStrip(oldView.getImage(), half + i * stripW, stripW, h, true);
                    strip.setLayoutX(half + i * stripW);
                    strip.setLayoutY(0);
                    strip.setRotationAxis(Rotate.Y_AXIS);
                    strip.getTransforms().add(new Shear(0.0, -0.018, 0, h / 2.0));
                    transitionOverlay.getChildren().add(strip);

                    double targetX = half - ((strips - i) * stripW * 0.94);
                    double delay = (strips - 1 - i) * 11.0;
                    moveX = targetX - strip.getLayoutX();
                    animations.add(stripCurl(strip, moveX, -82, 0.38, delay, paperEase));
                }
                LOG.info("moveX=" + moveX);
                FadeTransition reveal = new FadeTransition(Duration.millis(430), newRight);
                reveal.setDelay(Duration.millis(80));
                reveal.setFromValue(0.0);
                reveal.setToValue(1.0);
                animations.add(reveal);
                ParallelTransition pt = new ParallelTransition(animations.toArray(new Animation[0]));
                pt.setOnFinished(e -> endTurnAnimation());
                pt.play();
                return;
            }

            newLeft.setOpacity(0.0);
            transitionOverlay.getChildren().setAll(newLeft, newRight, oldRight, gutterShadow);
            for (int i = 0; i < strips; i++) {
                StackPane strip = pageStrip(oldView.getImage(), i * stripW, stripW, h, false);
                strip.setLayoutX(i * stripW);
                strip.setLayoutY(0);
                strip.setRotationAxis(Rotate.Y_AXIS);
                strip.getTransforms().add(new Shear(0.0, 0.018, stripW, h / 2.0));
                transitionOverlay.getChildren().add(strip);

                double targetX = half + ((strips - 1 - i) * stripW * 0.94);
                double delay = i * 11.0;
                moveX = targetX - strip.getLayoutX();
                animations.add(stripCurl(strip, moveX, 82, 0.38, delay, paperEase));
            }
            LOG.info("moveX=" + moveX);
            FadeTransition reveal = new FadeTransition(Duration.millis(430), newLeft);
            reveal.setDelay(Duration.millis(80));
            reveal.setFromValue(0.0);
            reveal.setToValue(1.0);
            animations.add(reveal);
            ParallelTransition pt = new ParallelTransition(animations.toArray(new Animation[0]));
            pt.setOnFinished(e -> endTurnAnimation());
            pt.play();
        }

        private StackPane pageStrip(Image img, double sourceX, double stripW, double h, boolean forward) {
            ImageView slice = halfView(img, sourceX, stripW + 1, h);
            Rectangle shade = new Rectangle(Math.max(1, stripW + 1), h);
            shade.setMouseTransparent(true);
            shade.setFill(new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                forward
                    ? new Stop[]{new Stop(0, Color.color(0, 0, 0, 0.30)), new Stop(0.55, Color.color(0, 0, 0, 0.04)), new Stop(1, Color.color(1, 1, 1, 0.22))}
                    : new Stop[]{new Stop(0, Color.color(1, 1, 1, 0.22)), new Stop(0.45, Color.color(0, 0, 0, 0.04)), new Stop(1, Color.color(0, 0, 0, 0.30))}
            ));
            Rectangle edge = new Rectangle(2, h);
            edge.setMouseTransparent(true);
            edge.setFill(Color.color(1, 1, 1, 0.45));
            StackPane strip = new StackPane(slice, shade, edge);
            strip.setManaged(false);
            strip.setPrefSize(stripW + 1, h);
            strip.setMaxSize(stripW + 1, h);
            StackPane.setAlignment(edge, forward ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
            return strip;
        }

        private Animation stripCurl(StackPane strip, double moveX, double angle, double scaleX, double delayMs, Interpolator paperEase) {
            RotateTransition rot = new RotateTransition(Duration.millis(560), strip);
            rot.setDelay(Duration.millis(delayMs));
            rot.setFromAngle(0);
            rot.setToAngle(angle);
            rot.setInterpolator(paperEase);

            TranslateTransition move = new TranslateTransition(Duration.millis(560), strip);
            move.setDelay(Duration.millis(delayMs));
            move.setToX(moveX);
            move.setInterpolator(paperEase);

            ScaleTransition scale = new ScaleTransition(Duration.millis(560), strip);
            scale.setDelay(Duration.millis(delayMs));
            scale.setToX(scaleX);
            scale.setToY(0.985);
            scale.setInterpolator(paperEase);

            FadeTransition fade = new FadeTransition(Duration.millis(260), strip);
            fade.setDelay(Duration.millis(delayMs + 300));
            fade.setFromValue(1.0);
            fade.setToValue(0.12);

            return new ParallelTransition(rot, move, scale, fade);
        }

        private PerspectiveTransform pagePerspective(double half, double h) {
            PerspectiveTransform p = new PerspectiveTransform();
            p.setUlx(0); p.setUly(0);
            p.setUrx(half); p.setUry(0);
            p.setLrx(half); p.setLry(h);
            p.setLlx(0); p.setLly(h);
            return p;
        }

        private Timeline pagePerspectiveTimeline(PerspectiveTransform p, double half, double h, boolean forward) {
            double pinch = half * 0.16;
            double topCurl = 28;
            double bottomCurl = h - 28;
            KeyFrame start = new KeyFrame(Duration.ZERO,
                new KeyValue(p.ulxProperty(), 0),
                new KeyValue(p.ulyProperty(), 0),
                new KeyValue(p.urxProperty(), half),
                new KeyValue(p.uryProperty(), 0),
                new KeyValue(p.lrxProperty(), half),
                new KeyValue(p.lryProperty(), h),
                new KeyValue(p.llxProperty(), 0),
                new KeyValue(p.llyProperty(), h)
            );
            KeyFrame mid;
            KeyFrame end;
            if (forward) {
                mid = new KeyFrame(Duration.millis(250),
                    new KeyValue(p.ulxProperty(), pinch, Interpolator.EASE_BOTH),
                    new KeyValue(p.ulyProperty(), 10, Interpolator.EASE_BOTH),
                    new KeyValue(p.urxProperty(), half * 0.92, Interpolator.EASE_BOTH),
                    new KeyValue(p.uryProperty(), topCurl, Interpolator.EASE_BOTH),
                    new KeyValue(p.lrxProperty(), half * 0.92, Interpolator.EASE_BOTH),
                    new KeyValue(p.lryProperty(), bottomCurl, Interpolator.EASE_BOTH),
                    new KeyValue(p.llxProperty(), pinch, Interpolator.EASE_BOTH),
                    new KeyValue(p.llyProperty(), h - 10, Interpolator.EASE_BOTH)
                );
                end = new KeyFrame(Duration.millis(520),
                    new KeyValue(p.ulxProperty(), half * 0.36, Interpolator.EASE_OUT),
                    new KeyValue(p.ulyProperty(), 34, Interpolator.EASE_OUT),
                    new KeyValue(p.urxProperty(), half * 0.86, Interpolator.EASE_OUT),
                    new KeyValue(p.uryProperty(), 58, Interpolator.EASE_OUT),
                    new KeyValue(p.lrxProperty(), half * 0.86, Interpolator.EASE_OUT),
                    new KeyValue(p.lryProperty(), h - 58, Interpolator.EASE_OUT),
                    new KeyValue(p.llxProperty(), half * 0.36, Interpolator.EASE_OUT),
                    new KeyValue(p.llyProperty(), h - 34, Interpolator.EASE_OUT)
                );
            } else {
                mid = new KeyFrame(Duration.millis(250),
                    new KeyValue(p.ulxProperty(), half * 0.08, Interpolator.EASE_BOTH),
                    new KeyValue(p.ulyProperty(), topCurl, Interpolator.EASE_BOTH),
                    new KeyValue(p.urxProperty(), half - pinch, Interpolator.EASE_BOTH),
                    new KeyValue(p.uryProperty(), 10, Interpolator.EASE_BOTH),
                    new KeyValue(p.lrxProperty(), half - pinch, Interpolator.EASE_BOTH),
                    new KeyValue(p.lryProperty(), h - 10, Interpolator.EASE_BOTH),
                    new KeyValue(p.llxProperty(), half * 0.08, Interpolator.EASE_BOTH),
                    new KeyValue(p.llyProperty(), bottomCurl, Interpolator.EASE_BOTH)
                );
                end = new KeyFrame(Duration.millis(520),
                    new KeyValue(p.ulxProperty(), half * 0.14, Interpolator.EASE_OUT),
                    new KeyValue(p.ulyProperty(), 58, Interpolator.EASE_OUT),
                    new KeyValue(p.urxProperty(), half * 0.64, Interpolator.EASE_OUT),
                    new KeyValue(p.uryProperty(), 34, Interpolator.EASE_OUT),
                    new KeyValue(p.lrxProperty(), half * 0.64, Interpolator.EASE_OUT),
                    new KeyValue(p.lryProperty(), h - 34, Interpolator.EASE_OUT),
                    new KeyValue(p.llxProperty(), half * 0.14, Interpolator.EASE_OUT),
                    new KeyValue(p.llyProperty(), h - 58, Interpolator.EASE_OUT)
                );
            }
            return new Timeline(start, mid, end);
        }

        private Timeline movingShadowTimeline(Rectangle shadow, Rectangle edgeLight, double half, boolean forward) {
            double startX = forward ? 0 : half - shadow.getWidth();
            double endX = forward ? half * 0.42 : half * 0.08;
            double edgeStart = forward ? 0 : half - edgeLight.getWidth();
            double edgeEnd = forward ? half * 0.38 : half * 0.1;
            return new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(shadow.translateXProperty(), startX),
                    new KeyValue(edgeLight.translateXProperty(), edgeStart)
                ),
                new KeyFrame(Duration.millis(520),
                    new KeyValue(shadow.translateXProperty(), endX, Interpolator.EASE_BOTH),
                    new KeyValue(edgeLight.translateXProperty(), edgeEnd, Interpolator.EASE_BOTH)
                )
            );
        }

        private Rectangle spineShadow(double h) {
            Rectangle r = new Rectangle(18, h);
            r.setManaged(false);
            r.setMouseTransparent(true);
            r.setLayoutX(Math.max(0, pageLayer.getWidth() / 2.0 - 9));
            r.setFill(new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0, 0, 0, 0.02)),
                new Stop(0.5, Color.color(0, 0, 0, 0.18)),
                new Stop(1, Color.color(0, 0, 0, 0.02))
            ));
            return r;
        }

        private Rectangle curlShadow(double half, double h, boolean forward) {
            Rectangle r = new Rectangle(half, h);
            r.setManaged(false);
            r.setMouseTransparent(true);
            r.setOpacity(0.72);
            r.setFill(new LinearGradient(
                0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                forward
                    ? new Stop[]{new Stop(0, Color.color(0, 0, 0, 0.36)), new Stop(0.22, Color.color(0, 0, 0, 0.14)), new Stop(1, Color.color(0, 0, 0, 0.0))}
                    : new Stop[]{new Stop(0, Color.color(0, 0, 0, 0.0)), new Stop(0.78, Color.color(0, 0, 0, 0.14)), new Stop(1, Color.color(0, 0, 0, 0.36))}
            ));
            return r;
        }

        private Rectangle curlEdgeLight(double h, boolean forward) {
            Rectangle r = new Rectangle(5, h);
            r.setManaged(false);
            r.setMouseTransparent(true);
            r.setLayoutX(forward ? 0 : Math.max(0, pageLayer.getWidth() / 2.0 - 5));
            r.setFill(Color.color(1, 1, 1, 0.62));
            return r;
        }

        private void endTurnAnimation() {
            transitionOverlay.getChildren().clear();
            pages.setVisible(true);
            highlightOverlay.setVisible(true);
            watermark.setVisible(true);
            annotation.setVisible(true);
            turnAnimating = false;
            updateNavButtons();
        }

        private void updateNavButtons() {
            if (firstBtn == null || prevBtn == null || nextBtn == null || lastBtn == null) return;
            try {
                int total = reader.totalPages();
                firstBtn.setDisable(turnAnimating || currentPage <= 1);
                prevBtn.setDisable(turnAnimating || currentPage <= 1);
                int last = spreadMode ? lastSpreadStart(total) : total;
                nextBtn.setDisable(turnAnimating || currentPage >= last);
                lastBtn.setDisable(turnAnimating || currentPage >= last);
            } catch (Exception ex) {
                firstBtn.setDisable(turnAnimating || currentPage <= 1);
                prevBtn.setDisable(turnAnimating || currentPage <= 1);
                nextBtn.setDisable(turnAnimating);
                lastBtn.setDisable(turnAnimating);
            }
        }

        private void updatePageJumpText() {
            if (pageJump != null && !pageJump.isFocused()) {
                pageJump.setText(String.valueOf(currentPage));
            }
        }

        private void zoomIn() {
            zoomScale = Math.min(ZOOM_MAX, zoomScale + ZOOM_STEP);
            LOG.info("Zoom in -> " + (int)Math.round(zoomScale * 100) + "%");
            applyZoom();
        }

        private void zoomOut() {
            zoomScale = Math.max(ZOOM_MIN, zoomScale - ZOOM_STEP);
            LOG.info("Zoom out -> " + (int)Math.round(zoomScale * 100) + "%");
            applyZoom();
        }

        private void applyZoom() {
            Bounds vp = pageScroll.getViewportBounds();
            double vpW = vp == null ? 1000 : Math.max(200, vp.getWidth() - 10);
            double baseFitHeight = computeBaseFitHeight(vp);
            resetOverlayBoundsForLayout();
            if (spreadMode) {
                left.setFitWidth(0);
                right.setFitWidth(0);
            } else {
                left.setFitWidth(0);
                right.setFitWidth(vpW);
            }
            left.setFitHeight(baseFitHeight);
            right.setFitHeight(baseFitHeight);
            if (zoomScale <= 1.0) {
                zoomScale = 1.0;
                pageScroll.setHbarPolicy(spreadMode ? ScrollPane.ScrollBarPolicy.AS_NEEDED : ScrollPane.ScrollBarPolicy.NEVER);
                pageScroll.setVbarPolicy(spreadMode ? ScrollPane.ScrollBarPolicy.NEVER : ScrollPane.ScrollBarPolicy.AS_NEEDED);
            } else {
                pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            }
            zoomTransform.setX(zoomScale);
            zoomTransform.setY(zoomScale);
            zoomLabel.setText((int)Math.round(zoomScale * 100) + "%");
            selectionRect.setVisible(false);
            pageLayer.applyCss();
            pageLayer.layout();
            pages.applyCss();
            pages.layout();
            resizePageOverlays();
            pageLayer.applyCss();
            pageLayer.layout();
            redrawWatermark();
            redrawAnnotations();
            redrawHighlights();
            redrawSearchHighlights();
        }

        private void resetOverlayBoundsForLayout() {
            annotation.setWidth(1);
            annotation.setHeight(1);
            watermark.setWidth(1);
            watermark.setHeight(1);
            highlightOverlay.setPrefSize(1, 1);
            searchOverlay.setPrefSize(1, 1);
            transitionOverlay.setPrefSize(1, 1);
            pageLayer.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            pageLayer.setMaxSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        }

        private void resizePageOverlays() {
            Bounds b = pages.getBoundsInParent();
            double w = Math.max(1.0, b.getMinX() + b.getWidth());
            double h = Math.max(1.0, b.getHeight());
            annotation.setWidth(w);
            annotation.setHeight(h);
            watermark.setWidth(w);
            watermark.setHeight(h);
            highlightOverlay.setPrefSize(w, h);
            searchOverlay.setPrefSize(w, h);
            transitionOverlay.setPrefSize(w, h);
            pageLayer.setPrefSize(w, h);
            pageLayer.setMaxSize(w, h);
        }

        private double computeBaseFitHeight(Bounds viewport) {
            double vpW = viewport == null ? 1000 : Math.max(200, viewport.getWidth() - 10);
            double vpH = viewport == null ? 700 : Math.max(200, viewport.getHeight() - 10);
            double hFromHeight = vpH;

            Image li = left.getImage();
            Image ri = right.getImage();
            if (!spreadMode) {
                double ratio = safeRatio(ri != null ? ri : li);
                return Math.max(120, vpW / ratio);
            }
            return hFromHeight;
        }

        private double safeRatio(Image img) {
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return 0.7;
            return img.getWidth() / img.getHeight();
        }

        private void addBookmark() {
            TextInputDialog d = new TextInputDialog("Page " + currentPage);
            d.setTitle("Tambah Bookmark"); d.setHeaderText("Simpan bookmark halaman " + currentPage); d.setContentText("Nama bookmark:");
            Optional<String> r = d.showAndWait();
            if (r.isPresent()) {
                String label = r.get().trim();
                if (label.isEmpty()) { status.setText("Nama bookmark tidak boleh kosong"); return; }
                try { store.addBookmark(item.ebookId(), currentPage, label); LOG.info("Bookmark saved ebookId=" + item.ebookId() + " page=" + currentPage + " label=" + label); reloadBookmarks(); } catch (Exception ex) { LOG.log(Level.WARNING, "Bookmark save failed", ex); status.setText("Gagal simpan bookmark: " + ex.getMessage()); }
            }
        }

        private void addNote() {
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle("Tambah Note");
            ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            TextField title = new TextField("Page " + currentPage);
            TextArea content = new TextArea(); content.setPrefRowCount(5);
            d.getDialogPane().setContent(new VBox(8, new Label("Title"), title, new Label("Content"), content));
            if (d.showAndWait().orElse(ButtonType.CANCEL) == save) {
                if (content.getText().trim().isEmpty()) { status.setText("Isi note tidak boleh kosong"); return; }
                try { store.addNote(item.ebookId(), currentPage, title.getText().trim(), content.getText().trim()); LOG.info("Note saved ebookId=" + item.ebookId() + " page=" + currentPage); reloadNotes(); } catch (Exception ex) { LOG.log(Level.WARNING, "Note save failed", ex); status.setText("Gagal simpan note: " + ex.getMessage()); }
            }
        }

        private void reloadToc() {
            try {
                tocList.getItems().setAll(reader.readToc());
            } catch (Exception ex) {
                status.setText("Gagal load TOC: " + ex.getMessage());
            }
        }

        private void persistCurrentStroke() {
            if (currentStroke.isEmpty()) {
                return;
            }
            try {
                Map<Integer, List<List<List<Double>>>> pathsByPage = splitStrokeByPage();
                for (Map.Entry<Integer, List<List<List<Double>>>> entry : pathsByPage.entrySet()) {
                    persistStrokeForPage(entry.getKey(), entry.getValue());
                }
                currentStroke.clear();
                currentStrokePage = -1;
                reloadAnnotations();
                redrawAnnotations();
            } catch (Exception ex) { status.setText("Gagal simpan annotation: " + ex.getMessage()); }
        }

        private Map<Integer, List<List<List<Double>>>> splitStrokeByPage() {
            LinkedHashMap<Integer, List<List<List<Double>>>> out = new LinkedHashMap<>();
            try {
                Map<Integer, Bounds> pageBounds = visiblePageBounds();
                if (currentStroke.size() == 1) {
                    List<Double> p = currentStroke.get(0);
                    int page = pageAtPoint(p.get(0), p.get(1));
                    if (page > 0) {
                        List<List<Double>> path = new ArrayList<>();
                        appendStrokePoint(path, p.get(0), p.get(1));
                        out.computeIfAbsent(page, ignored -> new ArrayList<>()).add(path);
                    }
                    return out;
                }
                for (int i = 1; i < currentStroke.size(); i++) {
                    List<Double> a = currentStroke.get(i - 1);
                    List<Double> b = currentStroke.get(i);
                    double x0 = a.get(0);
                    double y0 = a.get(1);
                    double x1 = b.get(0);
                    double y1 = b.get(1);
                    for (Map.Entry<Integer, Bounds> entry : pageBounds.entrySet()) {
                        double[] clipped = clipSegmentToBounds(x0, y0, x1, y1, entry.getValue());
                        if (clipped == null) continue;
                        List<List<List<Double>>> paths = out.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
                        List<List<Double>> path = findAppendablePath(paths, clipped[0], clipped[1]);
                        if (path == null) {
                            path = new ArrayList<>();
                            paths.add(path);
                        }
                        appendStrokePoint(path, clipped[0], clipped[1]);
                        appendStrokePoint(path, clipped[2], clipped[3]);
                    }
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Annotation stroke split failed", ex);
            }
            return out;
        }

        private List<List<Double>> findAppendablePath(List<List<List<Double>>> paths, double x, double y) {
            if (paths.isEmpty()) return null;
            List<List<Double>> lastPath = paths.get(paths.size() - 1);
            if (lastPath.isEmpty()) return lastPath;
            List<Double> last = lastPath.get(lastPath.size() - 1);
            if (Math.abs(last.get(0) - x) < 1.0 && Math.abs(last.get(1) - y) < 1.0) {
                return lastPath;
            }
            return null;
        }

        private void appendStrokePoint(List<List<Double>> points, double x, double y) {
            if (!points.isEmpty()) {
                List<Double> last = points.get(points.size() - 1);
                if (Math.abs(last.get(0) - x) < 0.25 && Math.abs(last.get(1) - y) < 0.25) {
                    return;
                }
            }
            points.add(List.of(x, y));
        }

        private double[] clipSegmentToBounds(double x0, double y0, double x1, double y1, Bounds b) {
            double dx = x1 - x0;
            double dy = y1 - y0;
            double t0 = 0.0;
            double t1 = 1.0;
            double[] p = {-dx, dx, -dy, dy};
            double[] q = {x0 - b.getMinX(), b.getMaxX() - x0, y0 - b.getMinY(), b.getMaxY() - y0};
            for (int i = 0; i < 4; i++) {
                if (Math.abs(p[i]) < 0.000001) {
                    if (q[i] < 0) return null;
                    continue;
                }
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return null;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return null;
                    if (r < t1) t1 = r;
                }
            }
            return new double[] {
                x0 + t0 * dx,
                y0 + t0 * dy,
                x0 + t1 * dx,
                y0 + t1 * dy
            };
        }

        private void persistStrokeForPage(int page, List<List<List<Double>>> paths) throws Exception {
            List<List<List<Double>>> validPaths = paths.stream().filter(p -> p.size() >= 2).toList();
            if (validPaths.isEmpty()) return;
            Bounds pageBounds = visiblePageBounds().get(page);
            if (pageBounds == null) return;
            LocalBookReader.PageTextMap map = reader.readPageTextMap(page);
            double pageW = map.width();
            double pageH = map.height();
            if (pageW <= 0 || pageH <= 0) return;
            double sx = pageW / Math.max(1.0, pageBounds.getWidth());
            double sy = pageH / Math.max(1.0, pageBounds.getHeight());
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"version\":2,");
            sb.append("\"color\":\"").append(toHex(annotationColor.getValue())).append("\",");
            sb.append("\"thickness\":").append(annotationThickness.getValue()).append(",");
            sb.append("\"pageWidth\":").append(pageW).append(",");
            sb.append("\"pageHeight\":").append(pageH).append(",");
            sb.append("\"paths\":[");
            for (int pathIndex = 0; pathIndex < validPaths.size(); pathIndex++) {
                List<List<Double>> points = validPaths.get(pathIndex);
                sb.append("[");
                for (int i = 0; i < points.size(); i++) {
                    List<Double> p = points.get(i);
                    double px = (p.get(0) - pageBounds.getMinX()) * sx;
                    double py = (p.get(1) - pageBounds.getMinY()) * sy;
                    sb.append("[").append(px).append(",").append(py).append("]");
                    if (i < points.size() - 1) sb.append(",");
                }
                sb.append("]");
                if (pathIndex < validPaths.size() - 1) sb.append(",");
            }
            sb.append("]}");
            store.addAnnotation(item.ebookId(), page, sb.toString());
        }

        private void undoAnnotation() {
            try {
                store.deleteLastAnnotation(item.ebookId(), currentPage);
                redrawAnnotations();
                reloadAnnotations();
                status.setText("Undo annotation berhasil");
            } catch (Exception ex) {
                status.setText("Gagal undo annotation: " + ex.getMessage());
            }
        }

        private void clearAnnotation() {
            Alert c = new Alert(Alert.AlertType.CONFIRMATION, "Semua annotation halaman ini akan dihapus.");
            c.setHeaderText("Clear annotation halaman " + currentPage + "?");
            if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            try {
                store.clearAnnotations(item.ebookId(), currentPage);
                redrawAnnotations();
                reloadAnnotations();
                status.setText("Annotation halaman dibersihkan");
            } catch (Exception ex) {
                status.setText("Gagal clear annotation: " + ex.getMessage());
            }
        }

        private ContextMenu buildBookmarkContextMenu() {
            MenuItem go = new MenuItem("Go To");
            go.setOnAction(e -> { BookmarkItem bm = bookmarkList.getSelectionModel().getSelectedItem(); if (bm != null) { currentPage = bm.pageNo(); render(); }});
            MenuItem ren = new MenuItem("Rename");
            ren.setOnAction(e -> { BookmarkItem bm = bookmarkList.getSelectionModel().getSelectedItem(); if (bm == null) return; TextInputDialog d = new TextInputDialog(bm.label()); d.setContentText("Label:"); Optional<String> r = d.showAndWait(); if (r.isPresent() && !r.get().isBlank()) try { store.renameBookmark(bm.id(), r.get().trim()); reloadBookmarks(); } catch (Exception ex) { status.setText("Gagal rename bookmark: " + ex.getMessage()); }});
            MenuItem del = new MenuItem("Delete");
            del.setOnAction(e -> { BookmarkItem bm = bookmarkList.getSelectionModel().getSelectedItem(); if (bm == null) return; Alert c = new Alert(Alert.AlertType.CONFIRMATION, "Aksi ini tidak bisa dibatalkan."); c.setHeaderText("Hapus bookmark \"" + bm.label() + "\"?"); if (c.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) try { store.deleteBookmark(bm.id()); reloadBookmarks(); } catch (Exception ex) { status.setText("Gagal hapus bookmark: " + ex.getMessage()); }});
            return new ContextMenu(go, ren, del);
        }

        private ContextMenu buildNoteContextMenu() {
            MenuItem go = new MenuItem("Go To");
            go.setOnAction(e -> { NoteItem n = noteList.getSelectionModel().getSelectedItem(); if (n != null) { currentPage = n.pageNo(); render(); }});
            MenuItem edit = new MenuItem("Edit");
            edit.setOnAction(e -> {
                NoteItem n = noteList.getSelectionModel().getSelectedItem();
                if (n == null) return;
                Dialog<ButtonType> d = new Dialog<>();
                d.setTitle("Edit Note");
                ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
                d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
                TextField title = new TextField(n.title() == null ? "" : n.title());
                TextArea content = new TextArea(n.content()); content.setPrefRowCount(5);
                d.getDialogPane().setContent(new VBox(8, new Label("Title"), title, new Label("Content"), content));
                if (d.showAndWait().orElse(ButtonType.CANCEL) == save) {
                    if (content.getText().trim().isEmpty()) { status.setText("Isi note tidak boleh kosong"); return; }
                    try { store.updateNote(n.id(), title.getText().trim(), content.getText().trim()); reloadNotes(); } catch (Exception ex) { status.setText("Gagal update note: " + ex.getMessage()); }
                }
            });
            MenuItem del = new MenuItem("Delete");
            del.setOnAction(e -> {
                NoteItem n = noteList.getSelectionModel().getSelectedItem();
                if (n == null) return;
                String title = (n.title() == null || n.title().isBlank()) ? "(No Title)" : n.title();
                Alert c = new Alert(Alert.AlertType.CONFIRMATION, "Aksi ini tidak bisa dibatalkan.");
                c.setHeaderText("Hapus note \"" + title + "\"?");
                if (c.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) try { store.deleteNote(n.id()); reloadNotes(); } catch (Exception ex) { status.setText("Gagal hapus note: " + ex.getMessage()); }
            });
            return new ContextMenu(go, edit, del);
        }

        private void reloadBookmarks() { try { bookmarkList.getItems().setAll(store.listBookmarks(item.ebookId())); } catch (Exception ex) { status.setText("Gagal load bookmark: " + ex.getMessage()); } }
        private void reloadHighlights() { try { highlightList.getItems().setAll(store.listHighlightPages(item.ebookId())); } catch (Exception ex) { status.setText("Gagal load highlight: " + ex.getMessage()); } }
        private void reloadNotes() {
            try {
                allNotes.clear();
                allNotes.addAll(store.listNotes(item.ebookId()));
                applyNoteFilter();
                if (!noteList.getItems().isEmpty()) {
                    noteList.getSelectionModel().select(0);
                    updateNotePreview(noteList.getSelectionModel().getSelectedItem());
                } else {
                    updateNotePreview(null);
                }
            } catch (Exception ex) {
                status.setText("Gagal load note: " + ex.getMessage());
            }
        }

        private ContextMenu buildHighlightContextMenu() {
            MenuItem goTo = new MenuItem("Go To");
            goTo.setOnAction(e -> {
                HighlightPageItem h = highlightList.getSelectionModel().getSelectedItem();
                if (h != null) {
                    currentPage = h.pageNo();
                    render();
                }
            });
            MenuItem delete = new MenuItem("Delete");
            delete.setOnAction(e -> {
                HighlightPageItem h = highlightList.getSelectionModel().getSelectedItem();
                if (h == null) return;
                Alert c = new Alert(Alert.AlertType.CONFIRMATION, "Semua highlight di halaman ini akan dihapus.");
                c.setHeaderText("Hapus semua highlight di page " + h.pageNo() + "?");
                if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                try {
                    store.clearHighlights(item.ebookId(), h.pageNo());
                    reloadHighlights();
                    redrawHighlights();
                } catch (Exception ex) {
                    status.setText("Gagal hapus highlight: " + ex.getMessage());
                }
            });
            return new ContextMenu(goTo, delete);
        }

        private void reloadAnnotations() {
            try {
                annotationList.getItems().setAll(store.listAnnotationPages(item.ebookId()));
            } catch (Exception ex) {
                status.setText("Gagal load annotation list: " + ex.getMessage());
            }
        }

        private ContextMenu buildAnnotationContextMenu() {
            MenuItem goTo = new MenuItem("Go To");
            goTo.setOnAction(e -> {
                AnnotationPageItem a = annotationList.getSelectionModel().getSelectedItem();
                if (a != null) {
                    currentPage = a.pageNo();
                    render();
                }
            });
            MenuItem delete = new MenuItem("Delete");
            delete.setOnAction(e -> {
                AnnotationPageItem a = annotationList.getSelectionModel().getSelectedItem();
                if (a == null) return;
                Alert c = new Alert(Alert.AlertType.CONFIRMATION, "Semua annotation di halaman ini akan dihapus.");
                c.setHeaderText("Hapus semua annotation di page " + a.pageNo() + "?");
                if (c.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                try {
                    store.clearAnnotations(item.ebookId(), a.pageNo());
                    reloadAnnotations();
                    redrawAnnotations();
                } catch (Exception ex) {
                    status.setText("Gagal hapus annotation: " + ex.getMessage());
                }
            });
            return new ContextMenu(goTo, delete);
        }

        private void applyNoteFilter() {
            String q = noteFilter.getText() == null ? "" : noteFilter.getText().trim().toLowerCase();
            if (q.isEmpty()) {
                noteList.getItems().setAll(allNotes);
                return;
            }
            List<NoteItem> filtered = new ArrayList<>();
            for (NoteItem n : allNotes) {
                String t = n.title() == null ? "" : n.title().toLowerCase();
                String c = n.content() == null ? "" : n.content().toLowerCase();
                String p = String.valueOf(n.pageNo());
                if (t.contains(q) || c.contains(q) || p.contains(q)) {
                    filtered.add(n);
                }
            }
            noteList.getItems().setAll(filtered);
        }

        private void updateNotePreview(NoteItem n) {
            if (n == null) {
                notePreviewTitle.setText("-");
                notePreviewMeta.setText("-");
                notePreviewContent.setText("");
                return;
            }
            String title = (n.title() == null || n.title().isBlank()) ? "(No Title)" : n.title();
            notePreviewTitle.setText(title);
            notePreviewMeta.setText("Page " + n.pageNo() + " | Updated: " + n.updatedAt());
            notePreviewContent.setText(n.content());
        }

        private void handleHighlightSelection(StackPane pageLayer) {
            if (selectionRect.getWidth() < 4 || selectionRect.getHeight() < 4) return;
            try {
                String color = toHex(highlightColor.getValue());
                for (Map.Entry<Integer, Bounds> entry : visiblePageBounds().entrySet()) {
                    Bounds b = entry.getValue();
                    if (intersects(selectionRect.getX(), selectionRect.getY(), selectionRect.getWidth(), selectionRect.getHeight(),
                        b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight())) {
                        persistSelectionForPage(entry.getKey(), b, color);
                    }
                }
                reloadHighlights();
                redrawHighlights();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Highlight selection failed", ex);
                status.setText("Gagal membuat highlight: " + ex.getMessage());
            }
        }

        private void persistSelectionForPage(int page, Bounds b, String color) throws Exception {
            LocalBookReader.PageTextMap map = reader.readPageTextMap(page);
            double pw = map.width();
            double ph = map.height();
            if (pw <= 0 || ph <= 0) return;
            double sx = pw / Math.max(1.0, b.getWidth());
            double sy = ph / Math.max(1.0, b.getHeight());
            double rx = (selectionRect.getX() - b.getMinX()) * sx;
            double ry = (selectionRect.getY() - b.getMinY()) * sy;
            double rw = selectionRect.getWidth() * sx;
            double rh = selectionRect.getHeight() * sy;
            for (LocalBookReader.TextSpanBox s : map.spans()) {
                if (intersects(rx, ry, rw, rh, s.x(), s.y(), s.w(), s.h())) {
                    String bbox = s.x() + "," + s.y() + "," + s.w() + "," + s.h();
                    store.addHighlight(item.ebookId(), page, s.text(), bbox, color);
                }
            }
        }

        private boolean intersects(double x1, double y1, double w1, double h1, double x2, double y2, double w2, double h2) {
            return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2;
        }

        private void performSearch(String query) {
            searchResults.clear();
            searchResultIndex = -1;
            redrawSearchHighlights();
            String q = query == null ? "" : query.trim();
            if (q.isBlank()) {
                updateSearchResultLabel();
                status.setText("Search kosong");
                return;
            }
            try {
                LOG.info("Search start query=" + q);
                List<String> tokens = searchTokens(q);
                int total = reader.totalPages();
                for (int page = 1; page <= total; page++) {
                    LocalBookReader.PageTextMap map = reader.readPageTextMap(page);
                    List<LocalBookReader.TextSpanBox> spans = map.spans();
                    for (int i = 0; i < spans.size(); i++) {
                        SearchResult r = matchSearchAt(page, spans, i, tokens);
                        if (r != null) {
                            searchResults.add(r);
                        }
                    }
                }
                if (searchResults.isEmpty()) {
                    updateSearchResultLabel();
                    status.setText("Search tidak menemukan hasil");
                    LOG.info("Search no result query=" + q);
                    return;
                }
                searchResultIndex = 0;
                goToSearchResult();
                LOG.info("Search found count=" + searchResults.size() + " query=" + q);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Search failed", ex);
                status.setText("Search error: " + ex.getMessage());
                searchResults.clear();
                searchResultIndex = -1;
                updateSearchResultLabel();
                redrawSearchHighlights();
            }
        }

        private List<String> searchTokens(String query) {
            return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(this::normalizeSearchWord)
                .filter(s -> !s.isBlank())
                .toList();
        }

        private String normalizeSearchWord(String text) {
            if (text == null) return "";
            return text.toLowerCase(Locale.ROOT).replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        }

        private SearchResult matchSearchAt(int page, List<LocalBookReader.TextSpanBox> spans, int start, List<String> tokens) {
            if (tokens.isEmpty() || start + tokens.size() > spans.size()) return null;
            double x0 = Double.MAX_VALUE;
            double y0 = Double.MAX_VALUE;
            double x1 = Double.MIN_VALUE;
            double y1 = Double.MIN_VALUE;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                LocalBookReader.TextSpanBox span = spans.get(start + i);
                String word = normalizeSearchWord(span.text());
                String token = tokens.get(i);
                boolean matched = tokens.size() == 1 ? word.contains(token) : word.equals(token);
                if (!matched) return null;
                if (!text.isEmpty()) text.append(" ");
                text.append(span.text());
                x0 = Math.min(x0, span.x());
                y0 = Math.min(y0, span.y());
                x1 = Math.max(x1, span.x() + span.w());
                y1 = Math.max(y1, span.y() + span.h());
            }
            if (x1 <= x0 || y1 <= y0) return null;
            return new SearchResult(page, text.toString(), x0, y0, x1 - x0, y1 - y0);
        }

        private void moveSearchResult(int delta) {
            if (searchResults.isEmpty()) {
                updateSearchResultLabel();
                status.setText("Belum ada hasil search");
                return;
            }
            searchResultIndex = Math.floorMod(searchResultIndex + delta, searchResults.size());
            goToSearchResult();
        }

        private void goToSearchResult() {
            if (searchResultIndex < 0 || searchResultIndex >= searchResults.size()) {
                updateSearchResultLabel();
                redrawSearchHighlights();
                return;
            }
            SearchResult r = searchResults.get(searchResultIndex);
            currentPage = spreadMode ? normalizePageForSpread(r.page()) : r.page();
            render();
            updateSearchResultLabel();
            status.setText("Search result " + (searchResultIndex + 1) + " / " + searchResults.size() + " di halaman " + r.page());
        }

        private void updateSearchResultLabel() {
            int current = searchResultIndex >= 0 && !searchResults.isEmpty() ? searchResultIndex + 1 : 0;
            searchResultLabel.setText(current + " / " + searchResults.size());
        }

        private void redrawSearchHighlights() {
            searchOverlay.getChildren().clear();
            if (searchResults.isEmpty()) return;
            try {
                for (Map.Entry<Integer, Bounds> entry : visiblePageBounds().entrySet()) {
                    drawSearchHighlightsForPage(entry.getKey(), entry.getValue());
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Search highlight render failed", ex);
            }
        }

        private void drawSearchHighlightsForPage(int page, Bounds b) throws Exception {
            LocalBookReader.PageTextMap map = reader.readPageTextMap(page);
            double pw = map.width();
            double ph = map.height();
            if (pw <= 0 || ph <= 0) return;
            double sx = Math.max(1.0, b.getWidth()) / pw;
            double sy = Math.max(1.0, b.getHeight()) / ph;
            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult r = searchResults.get(i);
                if (r.page() != page) continue;
                Rectangle box = new Rectangle(
                    b.getMinX() + r.x() * sx,
                    b.getMinY() + r.y() * sy,
                    Math.max(1.0, r.w() * sx),
                    Math.max(1.0, r.h() * sy)
                );
                boolean active = i == searchResultIndex;
                box.setFill(active ? Color.color(1.0, 0.62, 0.0, 0.42) : Color.color(1.0, 1.0, 0.0, 0.28));
                box.setStroke(active ? Color.ORANGE : Color.TRANSPARENT);
                box.setStrokeWidth(active ? 1.5 : 0.0);
                box.setMouseTransparent(true);
                searchOverlay.getChildren().add(box);
            }
        }

        private void redrawHighlights() {
            highlightOverlay.getChildren().clear();
            try {
                for (Map.Entry<Integer, Bounds> entry : visiblePageBounds().entrySet()) {
                    drawHighlightsForPage(entry.getKey(), entry.getValue());
                }
            } catch (Exception ex) {
                status.setText("Gagal render highlight: " + ex.getMessage());
            }
        }

        private Map<Integer, Bounds> visiblePageBounds() throws Exception {
            pageLayer.applyCss();
            pageLayer.layout();
            pages.applyCss();
            pages.layout();
            LinkedHashMap<Integer, Bounds> out = new LinkedHashMap<>();
            int total = reader.totalPages();
            if (!spreadMode) {
                if (right.getImage() != null) {
                    out.put(Math.min(currentPage, total), displayedImageBounds(right));
                }
                return out;
            }
            if (currentPage <= 1) {
                if (right.getImage() != null) out.put(1, displayedImageBounds(right));
                return out;
            }
            if (left.getImage() != null && currentPage <= total) {
                out.put(currentPage, displayedImageBounds(left));
            }
            int rightPage = currentPage + 1;
            if (right.getImage() != null && rightPage <= total) {
                out.put(rightPage, displayedImageBounds(right));
            }
            return out;
        }

        private Bounds displayedImageBounds(ImageView view) {
            Bounds node = view.getBoundsInParent();
            Image img = view.getImage();
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                return node;
            }
            double ratio = img.getWidth() / img.getHeight();
            double maxW = view.getFitWidth() > 0 ? view.getFitWidth() : node.getWidth();
            double maxH = view.getFitHeight() > 0 ? view.getFitHeight() : node.getHeight();
            double actualW = maxW;
            double actualH = maxW / ratio;
            if (actualH > maxH) {
                actualH = maxH;
                actualW = actualH * ratio;
            }
            double x = node.getMinX() + Math.max(0, (node.getWidth() - actualW) / 2.0);
            double y = node.getMinY() + Math.max(0, (node.getHeight() - actualH) / 2.0);
            return new BoundingBox(x, y, Math.max(1.0, actualW), Math.max(1.0, actualH));
        }

        private int pageAtPoint(double x, double y) {
            try {
                for (Map.Entry<Integer, Bounds> entry : visiblePageBounds().entrySet()) {
                    Bounds b = entry.getValue();
                    if (x >= b.getMinX() && x <= b.getMaxX() && y >= b.getMinY() && y <= b.getMaxY()) {
                        return entry.getKey();
                    }
                }
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Unable to resolve annotation page", ex);
            }
            return -1;
        }

        private void drawHighlightsForPage(int page, Bounds b) throws Exception {
            List<HighlightItem> rows = store.listHighlights(item.ebookId(), page);
            LocalBookReader.PageTextMap map = reader.readPageTextMap(page);
            double pw = map.width();
            double ph = map.height();
            if (pw <= 0 || ph <= 0) return;
            double sx = Math.max(1.0, b.getWidth()) / pw;
            double sy = Math.max(1.0, b.getHeight()) / ph;
            for (HighlightItem hi : rows) {
                if (hi.bboxJson() == null || hi.bboxJson().isBlank()) continue;
                String[] p = hi.bboxJson().split(",");
                if (p.length < 4) continue;
                double x = Double.parseDouble(p[0]);
                double y = Double.parseDouble(p[1]);
                double w = Double.parseDouble(p[2]);
                double h = Double.parseDouble(p[3]);
                Rectangle r = new Rectangle(
                    b.getMinX() + x * sx,
                    b.getMinY() + y * sy,
                    Math.max(1.0, w * sx),
                    Math.max(1.0, h * sy)
                );
                Color c = Color.web(hi.color() == null || hi.color().isBlank() ? "#ffff00" : hi.color(), 0.35);
                r.setFill(c);
                r.setStroke(Color.TRANSPARENT);
                highlightOverlay.getChildren().add(r);
            }
        }

        private void render() {
            try {
                int total = reader.totalPages();
                currentPage = Math.max(1, Math.min(currentPage, total));
                store.upsertProgress(item.ebookId(), currentPage);
                updatePageJumpText();
                updateNavButtons();
                left.setVisible(spreadMode);
                left.setManaged(spreadMode);
                right.setVisible(true);
                right.setManaged(true);
                if (!spreadMode) {
                    left.setImage(null);
                    right.setImage(reader.renderPageImage(currentPage));
                    applyZoom();
                    return;
                }
                if (currentPage <= 1) {
                    left.setImage(null);
                    right.setImage(reader.renderPageImage(1));
                    applyZoom();
                    return;
                }
                int leftPage = Math.min(currentPage, total);
                int rightPage = Math.min(currentPage + 1, total);
                left.setImage(reader.renderPageImage(leftPage));
                right.setImage(rightPage <= total ? reader.renderPageImage(rightPage) : null);
                applyZoom();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Render failed", ex);
                status.setText("Render error: " + ex.getMessage());
            }
        }

        private void redrawAnnotations() {
            try {
                GraphicsContext gc = annotation.getGraphicsContext2D();
                gc.clearRect(0, 0, annotation.getWidth(), annotation.getHeight());
                for (Map.Entry<Integer, Bounds> entry : visiblePageBounds().entrySet()) {
                    List<String> rows = store.listAnnotations(item.ebookId(), entry.getKey());
                    for (String row : rows) {
                        drawStrokeJson(row, gc, entry.getValue());
                    }
                }
            } catch (Exception ex) {
                status.setText("Gagal render annotation: " + ex.getMessage());
            }
        }

        private void redrawWatermark() {
            try {
                double w = Math.max(1, pageLayer == null ? watermark.getWidth() : pageLayer.getWidth());
                double h = Math.max(1, pageLayer == null ? watermark.getHeight() : pageLayer.getHeight());
                watermark.setWidth(w);
                watermark.setHeight(h);
                GraphicsContext gc = watermark.getGraphicsContext2D();
                gc.clearRect(0, 0, w, h);
                gc.save();
                gc.setGlobalAlpha(0.13);
                gc.setFill(Color.rgb(30, 30, 30));
                gc.setFont(javafx.scene.text.Font.font("SansSerif", 18));
                gc.setTextAlign(TextAlignment.CENTER);
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                gc.rotate(-28);
                for (double y = -h; y < h * 2; y += 120) {
                    for (double x = -w; x < w * 2; x += 360) {
                        gc.fillText(watermarkIdentity[0], x, y);
                        gc.fillText(watermarkIdentity[1], x, y + 24);
                        gc.fillText(ts, x, y + 48);
                    }
                }
                gc.restore();
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Watermark render failed", ex);
            }
        }

        private void drawStrokeJson(String json, GraphicsContext gc, Bounds pageBounds) {
            try {
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                String color = root.has("color") ? root.get("color").getAsString() : "#ff0000";
                double thickness = root.has("thickness") ? root.get("thickness").getAsDouble() : 2.0;
                boolean pageRelative = root.has("version") && root.get("version").getAsInt() >= 2;
                double pageW = root.has("pageWidth") ? root.get("pageWidth").getAsDouble() : 0.0;
                double pageH = root.has("pageHeight") ? root.get("pageHeight").getAsDouble() : 0.0;
                double sx = pageRelative && pageW > 0 ? pageBounds.getWidth() / pageW : 1.0;
                double sy = pageRelative && pageH > 0 ? pageBounds.getHeight() / pageH : 1.0;
                gc.setStroke(Color.web(color));
                gc.setLineWidth(thickness);
                if (root.has("paths") && root.get("paths").isJsonArray()) {
                    for (var pathElement : root.getAsJsonArray("paths")) {
                        if (pathElement.isJsonArray()) {
                            drawAnnotationPointPath(pathElement.getAsJsonArray(), gc, pageBounds, pageRelative, sx, sy);
                        }
                    }
                    return;
                }
                if (root.has("points") && root.get("points").isJsonArray()) {
                    drawAnnotationPointPath(root.getAsJsonArray("points"), gc, pageBounds, pageRelative, sx, sy);
                }
            } catch (Exception ignored) {
            }
        }

        private void drawAnnotationPointPath(JsonArray points, GraphicsContext gc, Bounds pageBounds, boolean pageRelative, double sx, double sy) {
            try {
                if (points == null || points.isEmpty()) return;
                double prevX = 0;
                double prevY = 0;
                boolean first = true;
                for (var pointElement : points) {
                    if (!pointElement.isJsonArray()) continue;
                    JsonArray xy = pointElement.getAsJsonArray();
                    if (xy.size() < 2) continue;
                    double rawX = xy.get(0).getAsDouble();
                    double rawY = xy.get(1).getAsDouble();
                    double x = pageRelative ? pageBounds.getMinX() + rawX * sx : rawX;
                    double y = pageRelative ? pageBounds.getMinY() + rawY * sy : rawY;
                    if (first) {
                        gc.beginPath();
                        gc.moveTo(x, y);
                        first = false;
                    } else {
                        gc.lineTo(x, y);
                    }
                    prevX = x;
                    prevY = y;
                }
                if (!first) {
                    gc.lineTo(prevX, prevY);
                    gc.stroke();
                }
            } catch (Exception ignored) {
            }
        }

        private double parseDoubleOr(String value, double fallback) {
            if (value == null || value.isBlank()) return fallback;
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private String extract(String source, String start, String end) {
            int a = source.indexOf(start);
            if (a < 0) return null;
            int s = a + start.length();
            int b = source.indexOf(end, s);
            if (b < 0) return source.substring(s);
            return source.substring(s, b);
        }

        private String toHex(Color c) {
            int r = (int)Math.round(c.getRed() * 255);
            int g = (int)Math.round(c.getGreen() * 255);
            int b = (int)Math.round(c.getBlue() * 255);
            return String.format("#%02x%02x%02x", r, g, b);
        }

        private record SearchResult(int page, String text, double x, double y, double w, double h) {}
    }
}
