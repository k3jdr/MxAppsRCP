package com.k3jdr.mxjobsmgr.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mxjobsmgr.model.JobLink;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Jobspace;
import com.k3jdr.mxjobsmgr.model.Layer;
import com.k3jdr.mxjobsmgr.model.Metrics;
import com.k3jdr.mxjobsmgr.model.NodeSummary;

/**
 * Saves the diagram as a PNG image (2×) or as a draw.io SVG (*.drawio.svg, DiagramExport.swift): an SVG drawing of
 * the lanes, nodes and links that also carries the diagram as draw.io XML in its {@code content} attribute, so
 * draw.io / diagrams.net opens it for editing (lanes become swimlanes holding their nodes, links connectors).
 */
public final class DiagramExport {
    private DiagramExport() {}

    private static String baseName(Jobspace space) {
        String n = space.name.trim();
        return (n.isEmpty() ? "Jobspace" : n).replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private static Path choose(Shell shell, String name, String ext, String filterName) {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setFileName(name);
        fd.setFilterExtensions(new String[] { "*" + ext, "*.*" });
        fd.setFilterNames(new String[] { filterName, "All files" });
        fd.setOverwrite(true);
        String chosen = fd.open();
        if (chosen == null) return null;
        // Keep the ".drawio" in "name.drawio.svg" if the dialog dropped it.
        if (ext.equals(".drawio.svg") && !chosen.toLowerCase(Locale.ROOT).endsWith(".drawio.svg")) {
            chosen = chosen.toLowerCase(Locale.ROOT).endsWith(".svg") ? chosen.substring(0, chosen.length() - 4) + ".drawio.svg" : chosen + ".drawio.svg";
        } else if (ext.equals(".png") && !chosen.toLowerCase(Locale.ROOT).endsWith(".png")) {
            chosen += ".png";
        }
        return Path.of(chosen);
    }

    public static void savePNG(Shell shell, Jobspace space) {
        Path file = choose(shell, baseName(space) + ".png", ".png", "PNG image (*.png)");
        if (file == null) return;
        try {
            Files.write(file, pngData(space, 2));
        } catch (Exception e) {
            ErrorDialog.show(shell, "Could not save the image", e);
        }
    }

    public static void saveDrawioSVG(Shell shell, Jobspace space) {
        Path file = choose(shell, baseName(space) + ".drawio.svg", ".drawio.svg", "draw.io SVG (*.drawio.svg)");
        if (file == null) return;
        try {
            Files.writeString(file, drawioSVG(space), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ErrorDialog.show(shell, "Could not save the drawing", e);
        }
    }

    // MARK: PNG

    /** The whole diagram as PNG at {@code scale} (UI thread). */
    public static byte[] pngData(Jobspace space, int scale) {
        Display display = Display.getCurrent();
        int w = (int) Math.ceil(Metrics.canvasWidth(space)), h = (int) Math.ceil(Metrics.canvasHeight());
        Image image = new Image(display, w * scale, h * scale);
        GC gc = new GC(image);
        Transform t = new Transform(display);
        try {
            gc.setAdvanced(true);
            t.scale(scale, scale);
            gc.setTransform(t);
            DiagramPainter.View view = new DiagramPainter.View();
            view.space = space;
            view.interactive = false;
            DiagramPainter.paint(gc, view, 0, 0, w, h);
            gc.setTransform(null);
            ImageData data = image.getImageData();
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { data };
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            loader.save(out, SWT.IMAGE_PNG);
            return out.toByteArray();
        } finally {
            t.dispose();
            gc.dispose();
            image.dispose();
        }
    }

    // MARK: draw.io SVG

    public static String drawioSVG(Jobspace space) {
        double width = Metrics.canvasWidth(space), height = Metrics.canvasHeight();
        int w = (int) width, h = (int) height;
        double nodeW = Metrics.NODE_W, nodeH = Metrics.NODE_H;
        String font = "'Segoe UI', -apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif";
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"").append(w).append("px\" height=\"").append(h)
                .append("px\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" content=\"").append(xml(drawioXML(space))).append("\">\n");
        svg.append("<rect x=\"0\" y=\"0\" width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"#FFFFFF\"/>\n\n");

        // Lanes with their title column.
        for (Layer layer : Layer.values()) {
            int top = (int) Metrics.laneTop(layer), lh = (int) Metrics.LANE_HEIGHT, g = (int) Metrics.GUTTER;
            svg.append("<rect x=\"0\" y=\"").append(top).append("\" width=\"").append(w).append("\" height=\"").append(lh)
                    .append("\" fill=\"").append(layer.tintHex()).append("\"/>\n");
            svg.append("<rect x=\"0\" y=\"").append(top).append("\" width=\"").append(g).append("\" height=\"").append(lh)
                    .append("\" fill=\"").append(layer.hex()).append("\" fill-opacity=\"0.12\"/>\n");
            svg.append("<line x1=\"0\" y1=\"").append(top + lh).append("\" x2=\"").append(w).append("\" y2=\"").append(top + lh)
                    .append("\" stroke=\"").append(layer.hex()).append("\" stroke-opacity=\"0.35\"/>\n");
            svg.append("<text x=\"12\" y=\"").append(top + 24).append("\" font-family=\"").append(font).append("\" font-size=\"11\" font-weight=\"800\" fill=\"")
                    .append(layer.hex()).append("\">LAYER ").append(layer.number).append("</text>\n");
            svg.append("<text x=\"12\" y=\"").append(top + 44).append("\" font-family=\"").append(font).append("\" font-size=\"14\" font-weight=\"600\" fill=\"#1D1D1F\">")
                    .append(xml(layer.title)).append("</text>\n\n");
            List<String> lines = wrap(layer.subtitle, 24);
            for (int i = 0; i < lines.size(); i++) {
                svg.append("<text x=\"12\" y=\"").append(top + 64 + i * 15).append("\" font-family=\"").append(font)
                        .append("\" font-size=\"11\" fill=\"#6E6E73\">").append(xml(lines.get(i))).append("</text>\n");
            }
        }

        // Links: calls down the layers solid, results up dashed.
        for (JobLink link : space.links) {
            JobNode a = space.node(link.from), b = space.node(link.to);
            if (a == null || b == null) continue;
            Metrics.Curve c = Metrics.curve(a.x, a.y, b.x, b.y);
            boolean upward = b.layer().number < a.layer().number;
            String color = (upward ? b.layer() : a.layer()).hex();
            svg.append("<path d=\"M").append(n(c.sx())).append(',').append(n(c.sy())).append(" C").append(n(c.c1x())).append(',').append(n(c.c1y()))
                    .append(' ').append(n(c.c2x())).append(',').append(n(c.c2y())).append(' ').append(n(c.ex())).append(',').append(n(c.ey()))
                    .append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"1.8\" stroke-linecap=\"round\"")
                    .append(upward ? " stroke-dasharray=\"7 5\"" : "").append("/>\n");
            double[] hd = c.arrowHead();
            svg.append("<polygon points=\"").append(n(hd[0])).append(',').append(n(hd[1])).append(' ').append(n(hd[2])).append(',').append(n(hd[3]))
                    .append(' ').append(n(hd[4])).append(',').append(n(hd[5])).append("\" fill=\"").append(color).append("\"/>\n");
            if (!link.label.isEmpty()) {
                double lw = link.label.length() * 6.2 + 12;
                svg.append("<rect x=\"").append(n(c.midX() - lw / 2)).append("\" y=\"").append(n(c.midY() - 9)).append("\" width=\"").append(n(lw))
                        .append("\" height=\"18\" rx=\"4\" fill=\"#FFFFFF\" stroke=\"").append(color).append("\" stroke-opacity=\"0.5\"/>\n");
                svg.append("<text x=\"").append(n(c.midX())).append("\" y=\"").append(n(c.midY() + 4)).append("\" text-anchor=\"middle\" font-family=\"")
                        .append(font).append("\" font-size=\"11\" fill=\"#6E6E73\">").append(xml(link.label)).append("</text>\n");
            }
        }

        // Nodes.
        for (JobNode node : space.nodes) {
            double x = node.x - nodeW / 2, y = node.y - nodeH / 2;
            String c = node.layer().hex();
            svg.append("<g").append(node.enabled ? "" : " opacity=\"0.5\"").append(">\n");
            svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y)).append("\" width=\"").append(n(nodeW)).append("\" height=\"").append(n(nodeH))
                    .append("\" rx=\"10\" fill=\"#FFFFFF\"/>\n");
            svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y)).append("\" width=\"").append(n(nodeW)).append("\" height=\"").append(n(nodeH))
                    .append("\" rx=\"10\" fill=\"").append(c).append("\" fill-opacity=\"0.10\" stroke=\"").append(c).append("\" stroke-opacity=\"0.55\"/>\n");
            svg.append("<path d=\"M").append(n(x + 10)).append(',').append(n(y)).append(" A10,10 0 0 0 ").append(n(x)).append(',').append(n(y + 10))
                    .append(" L").append(n(x)).append(',').append(n(y + nodeH - 10)).append(" A10,10 0 0 0 ").append(n(x + 10)).append(',').append(n(y + nodeH))
                    .append(" L").append(n(x + 4)).append(',').append(n(y + nodeH)).append(" L").append(n(x + 4)).append(',').append(n(y)).append(" Z\" fill=\"")
                    .append(c).append("\"/>\n");
            svg.append("<text x=\"").append(n(x + 12)).append("\" y=\"").append(n(y + 19)).append("\" font-family=\"").append(font)
                    .append("\" font-size=\"9\" font-weight=\"700\" fill=\"").append(c).append("\">").append(xml(node.kind.title.toUpperCase())).append("</text>\n");
            svg.append("<text x=\"").append(n(x + 12)).append("\" y=\"").append(n(y + 40)).append("\" font-family=\"").append(font)
                    .append("\" font-size=\"13\" font-weight=\"600\" fill=\"#1D1D1F\">").append(xml(clip(node.title, 26))).append("</text>\n");
            svg.append("<text x=\"").append(n(x + 12)).append("\" y=\"").append(n(y + 60)).append("\" font-family=\"").append(font)
                    .append("\" font-size=\"11\" fill=\"#6E6E73\">").append(xml(clip(NodeSummary.text(node, space), 32))).append("</text>\n");
            svg.append("</g>\n\n");
        }
        svg.append("</svg>\n");
        return svg.toString();
    }

    /** The diagram as an uncompressed draw.io file: one swimlane per layer, nodes inside their lane, links as connectors. */
    public static String drawioXML(Jobspace space) {
        double width = Metrics.canvasWidth(space), height = Metrics.canvasHeight();
        double nodeW = Metrics.NODE_W, nodeH = Metrics.NODE_H;
        StringBuilder cells = new StringBuilder("<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>");
        for (Layer layer : Layer.values()) {
            String style = "swimlane;horizontal=0;startSize=" + (int) Metrics.GUTTER + ";html=1;whiteSpace=wrap;"
                    + "fillColor=" + layer.hex() + ";fontColor=#FFFFFF;swimlaneFillColor=" + layer.tintHex() + ";"
                    + "strokeColor=" + layer.hex() + ";fontSize=14;fontStyle=1;";
            String label = "Layer " + layer.number + "<br>" + layer.title;
            cells.append("<mxCell id=\"lane-").append(layer.number).append("\" value=\"").append(xml(label)).append("\" style=\"").append(style)
                    .append("\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"0\" y=\"").append(n(Metrics.laneTop(layer))).append("\" width=\"")
                    .append(n(width)).append("\" height=\"").append(n(Metrics.LANE_HEIGHT)).append("\" as=\"geometry\"/></mxCell>");
        }
        for (JobNode node : space.nodes) {
            String c = node.layer().hex();
            String value = "<font style=\"font-size:9px\" color=\"" + c + "\"><b>" + html(node.kind.title.toUpperCase()) + "</b></font><br>"
                    + "<b>" + html(node.title) + "</b><br>"
                    + "<font style=\"font-size:10px\" color=\"#6E6E73\">" + html(NodeSummary.text(node, space)) + "</font>";
            String style = "rounded=1;arcSize=12;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacingLeft=10;"
                    + "spacingTop=2;fillColor=#FFFFFF;strokeColor=" + c + ";fontSize=12;" + (node.enabled ? "" : "opacity=50;");
            double x = node.x - nodeW / 2;
            double y = node.y - nodeH / 2 - Metrics.laneTop(node.layer());
            cells.append("<mxCell id=\"").append(node.id).append("\" value=\"").append(xml(value)).append("\" style=\"").append(style)
                    .append("\" vertex=\"1\" parent=\"lane-").append(node.layer().number).append("\"><mxGeometry x=\"").append(n(x)).append("\" y=\"")
                    .append(n(y)).append("\" width=\"").append(n(nodeW)).append("\" height=\"").append(n(nodeH)).append("\" as=\"geometry\"/></mxCell>");
        }
        for (JobLink link : space.links) {
            JobNode a = space.node(link.from), b = space.node(link.to);
            if (a == null || b == null) continue;
            boolean upward = b.layer().number < a.layer().number;
            String style = "edgeStyle=orthogonalEdgeStyle;curved=1;rounded=1;html=1;endArrow=block;endFill=1;"
                    + "strokeColor=" + (upward ? b.layer() : a.layer()).hex() + ";strokeWidth=2;fontSize=11;labelBackgroundColor=#FFFFFF;"
                    + (upward ? "dashed=1;" : "");
            cells.append("<mxCell id=\"").append(link.id).append("\" value=\"").append(xml(link.label)).append("\" style=\"").append(style)
                    .append("\" edge=\"1\" parent=\"1\" source=\"").append(link.from).append("\" target=\"").append(link.to)
                    .append("\"><mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>");
        }
        String stamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return "<mxfile host=\"MxJobsMGR\" modified=\"" + stamp + "\" agent=\"MxJobsMGR\" version=\"24.0.0\">"
                + "<diagram id=\"jobspace\" name=\"" + xml(space.name) + "\">"
                + "<mxGraphModel dx=\"" + (int) width + "\" dy=\"" + (int) height + "\" grid=\"1\" gridSize=\"10\" guides=\"1\" "
                + "tooltips=\"1\" connect=\"1\" arrows=\"1\" fold=\"1\" page=\"0\" pageScale=\"1\" math=\"0\" shadow=\"0\">"
                + "<root>" + cells + "</root></mxGraphModel></diagram></mxfile>";
    }

    // MARK: Helpers

    /** Escapes text for XML attributes and content. */
    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "&#10;");
    }

    /** Escapes text placed inside a draw.io html=1 label. */
    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String n(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    private static String clip(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                lines.add(line);
                line = "";
            }
            line += (line.isEmpty() ? "" : " ") + word;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
}
