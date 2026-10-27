package com.k3jdr.mxnoteone.ui;

import java.io.ByteArrayInputStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.store.Disk;

/** Containers showing a file kept in the notebook: pictures, attachments, and containers this port doesn't edit. */
final class AssetBoxes {
    private AssetBoxes() {}

    /** Loads an asset on the disk thread and hands it over on the UI thread. */
    static void load(ElementView view, String asset, java.util.function.Consumer<byte[]> done) {
        if (asset == null || view.page.ref == null) return;
        var ref = view.page.ref;
        var store = view.page.store;
        Disk.submit(() -> {
            byte[] data = store.assetData(asset, ref);
            view.getDisplay().asyncExec(() -> {
                if (!view.isDisposed()) done.accept(data);
            });
        });
    }

    static Image decode(ElementView owner, byte[] data) {
        if (data == null) return null;
        try {
            Image img = new Image(owner.getDisplay(), new ImageData(new ByteArrayInputStream(data)));
            owner.addListener(SWT.Dispose, e -> img.dispose());
            return img;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A picture: drawn at the container's width, keeping its proportions; double-click opens it. */
    static final class PictureBox extends ElementView {
        final Canvas canvas;
        Image image;
        byte[] data;
        double aspect = 0.75;

        PictureBox(PageView page, PageElement element) {
            super(page, element);
            canvas = new Canvas(this, SWT.DOUBLE_BUFFERED);
            canvas.setBackground(page.paperColor());
            canvas.addPaintListener(e -> paint(e.gc));
            canvas.addListener(SWT.MouseDown, e -> {
                if (e.button == 1) page.select(this);
            });
            canvas.addListener(SWT.MouseDoubleClick, e -> page.openAsset(element));
            canvas.setMenu(grip.getMenu());
            QuickHelp.provider(canvas, (c, p) -> new QuickHelpItem("Picture",
                    "Double-click to open it in a viewer; right-click to copy, save, or reset it to its original size. Drag the bar above it to move it, its right edge to resize it."));
            if (element.height > GRIP && element.width > 0) aspect = (element.height - GRIP) / element.width;
            place();
            AssetBoxes.load(this, element.asset, d -> {
                data = d;
                image = decode(this, d);
                if (image != null) {
                    Rectangle b = image.getBounds();
                    aspect = b.width > 0 ? (double) b.height / b.width : 0.75;
                    place();
                    page.contentResized();
                }
                canvas.redraw();
            });
        }

        @Override
        double minWidth() { return 24; }

        @Override
        Control content() { return canvas; }

        @Override
        double contentHeight() { return element.width * aspect; }

        private void paint(GC gc) {
            Rectangle r = canvas.getClientArea();
            if (image == null) {
                gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
                gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
                gc.drawText(data == null && element.asset != null ? "Loading picture…" : "Missing picture", 6, 6, true);
                return;
            }
            Rectangle b = image.getBounds();
            gc.setInterpolation(SWT.HIGH);
            gc.drawImage(image, 0, 0, b.width, b.height, 0, 0, r.width, r.height);
        }

        void resetSize() {
            if (image == null) return;
            var before = page.snapshot();
            setWidth(image.getBounds().width);
            page.pushUndo("Reset Size", before);
            page.contentChanged();
        }

        @Override
        void fillMenu(Menu menu) {
            item(menu, "Open in Viewer", () -> page.openAsset(element));
            item(menu, "Copy Picture", () -> page.copyPicture(image));
            item(menu, "Save Picture As…", () -> page.saveAssetAs(element));
            item(menu, "Reset to Original Size", this::resetSize);
            new MenuItem(menu, SWT.SEPARATOR);
            super.fillMenu(menu);
        }
    }

    /** A file attachment (or recording): its icon and name; double-click opens it. */
    static final class FileBox extends ElementView {
        final Canvas canvas;

        FileBox(PageView page, PageElement element) {
            super(page, element);
            canvas = new Canvas(this, SWT.DOUBLE_BUFFERED);
            canvas.setBackground(page.paperColor());
            canvas.addPaintListener(e -> {
                Rectangle r = canvas.getClientArea();
                GC gc = e.gc;
                gc.setAntialias(SWT.ON);
                int s = px(32);
                gc.setBackground(Gfx.color(235, 238, 243));
                gc.fillRoundRectangle(px(6), (r.height - s) / 2, s, s, px(6), px(6));
                gc.setForeground(Gfx.color(110, 118, 130));
                gc.drawRoundRectangle(px(6), (r.height - s) / 2, s, s, px(6), px(6));
                String ext = extension();
                gc.setFont(Gfx.font("Segoe UI", 9, true, false, page.zoom));
                var ee = gc.textExtent(ext);
                gc.drawText(ext, px(6) + (s - ee.x) / 2, (r.height - ee.y) / 2, true);
                gc.setForeground(Gfx.color(30, 30, 30));
                gc.setFont(Gfx.font("Segoe UI", 13, false, false, page.zoom));
                String name = element.fileName != null ? element.fileName : element.asset != null ? element.asset : "File";
                var ne = gc.textExtent(name);
                gc.drawText(name, px(46), (r.height - ne.y) / 2, true);
            });
            canvas.addListener(SWT.MouseDown, e -> {
                if (e.button == 1) page.select(this);
            });
            canvas.addListener(SWT.MouseDoubleClick, e -> page.openAsset(element));
            canvas.setMenu(grip.getMenu());
            QuickHelp.provider(canvas, (c, p) -> new QuickHelpItem(PageElement.AUDIO.equals(element.kind) ? "Audio Recording" : "File Attachment",
                    "A copy of the file kept in the notebook: double-click to open it; right-click to save a copy elsewhere."));
            place();
        }

        private String extension() {
            String n = element.fileName != null ? element.fileName : element.asset != null ? element.asset : "";
            int dot = n.lastIndexOf('.');
            String ext = dot < 0 ? "FILE" : n.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
            return ext.length() > 4 ? ext.substring(0, 4) : ext;
        }

        @Override
        double minWidth() { return 120; }

        @Override
        Control content() { return canvas; }

        @Override
        double contentHeight() { return 46; }

        @Override
        void fillMenu(Menu menu) {
            item(menu, "Open", () -> page.openAsset(element));
            item(menu, "Save As…", () -> page.saveAssetAs(element));
            new MenuItem(menu, SWT.SEPARATOR);
            super.fillMenu(menu);
        }
    }

    /**
     * A container of a kind made on the Mac that this port shows but doesn't edit — a score, diagram, map, 3D
     * model, DBML diagram, chart or video: its picture when the notebook has one, else its name. It can be moved,
     * resized and deleted; its data is kept as it was.
     */
    static final class OtherBox extends ElementView {
        final Canvas canvas;
        Image image;
        double aspect;

        OtherBox(PageView page, PageElement element) {
            super(page, element);
            aspect = element.width > 0 && element.height > GRIP ? (element.height - GRIP) / element.width : 0.5;
            canvas = new Canvas(this, SWT.DOUBLE_BUFFERED);
            canvas.setBackground(page.paperColor());
            canvas.addPaintListener(e -> {
                Rectangle r = canvas.getClientArea();
                if (image != null) {
                    Rectangle b = image.getBounds();
                    e.gc.drawImage(image, 0, 0, b.width, b.height, 0, 0, r.width, r.height);
                } else {
                    e.gc.setBackground(Gfx.color(246, 247, 249));
                    e.gc.fillRectangle(0, 0, r.width, r.height);
                    e.gc.setForeground(Gfx.color(Gfx.FRAME));
                    e.gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
                }
                e.gc.setForeground(Gfx.color(Gfx.GRAY_TEXT));
                e.gc.setFont(Gfx.uiFont(0, SWT.NORMAL));
                if (image == null) {
                    e.gc.drawText(element.kindTitle() + (title() != null ? ": " + title() : ""), 8, 8, true);
                    e.gc.drawText("Made on the Mac — shown here, edited in MxNoteONE for macOS.", 8, 28, true);
                }
            });
            canvas.addListener(SWT.MouseDown, e -> {
                if (e.button == 1) page.select(this);
            });
            canvas.setMenu(grip.getMenu());
            QuickHelp.provider(canvas, (c, p) -> new QuickHelpItem(element.kindTitle(),
                    "A " + element.kindTitle().toLowerCase(java.util.Locale.ROOT)
                            + " made in MxNoteONE for macOS. It shows here and keeps everything it holds; edit it on the Mac. You can move, resize or delete it."));
            place();
            String pic = element.preview != null ? element.preview : PageElement.DIAGRAM.equals(element.kind) ? null : null;
            AssetBoxes.load(this, pic, d -> {
                image = decode(this, d);
                canvas.redraw();
            });
        }

        private String title() {
            for (String k : new String[] { "chart", "score", "map", "cad" }) {
                var o = element.rawMember(k);
                if (o != null && o.has("title") && !o.get("title").isJsonNull()) return o.get("title").getAsString();
                if (o != null && o.has("name") && !o.get("name").isJsonNull()) return o.get("name").getAsString();
            }
            return element.fileName;
        }

        @Override
        double minWidth() { return 60; }

        @Override
        Control content() { return canvas; }

        @Override
        double contentHeight() { return Math.max(40, element.width * aspect); }
    }
}
