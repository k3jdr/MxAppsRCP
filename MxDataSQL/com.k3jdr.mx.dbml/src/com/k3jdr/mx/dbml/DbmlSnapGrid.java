package com.k3jdr.mx.dbml;

/**
 * A grid to snap to, in the canvas's own (zoomed, unscrolled) coordinates: the host's page grid, so it lines up
 * with it.
 */
public record DbmlSnapGrid(double step, DbmlPoint origin) {
}
