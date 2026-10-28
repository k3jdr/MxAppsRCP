package com.k3jdr.mxnoteone.ui;

import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.TransferData;

/** Drags inside the window (sections, groups, pages): the dragged thing stays in memory; only a token travels. */
final class LocalTransfer extends ByteArrayTransfer {
    private static final String NAME = "MxNoteONE-local-" + System.nanoTime();
    private static final int ID = registerType(NAME);
    private static final LocalTransfer INSTANCE = new LocalTransfer();

    /** What is being dragged. */
    static Object payload;

    static LocalTransfer get() { return INSTANCE; }

    @Override
    protected int[] getTypeIds() { return new int[] { ID }; }

    @Override
    protected String[] getTypeNames() { return new String[] { NAME }; }

    @Override
    protected void javaToNative(Object object, TransferData data) {
        super.javaToNative(new byte[] { 1 }, data);
    }

    @Override
    protected Object nativeToJava(TransferData data) {
        Object o = super.nativeToJava(data);
        return o == null ? null : payload;
    }

    @Override
    protected boolean validate(Object object) { return true; }
}
