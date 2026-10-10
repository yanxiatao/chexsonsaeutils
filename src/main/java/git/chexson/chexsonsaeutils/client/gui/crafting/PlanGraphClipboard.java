package git.chexson.chexsonsaeutils.client.gui.crafting;

import com.sun.jna.*;
import com.sun.jna.platform.win32.BaseTSD;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Windows 原生图片剪贴板，直接调用系统 ABI，不依赖 Minecraft 已禁用的 AWT 桌面工具包。 */
final class PlanGraphClipboard {
    private PlanGraphClipboard() {}
    static void copy(BufferedImage image, long owner) throws IOException {
        if (!Platform.isWindows()) throw new IOException("当前平台尚未提供图片剪贴板接口");
        var kernel = NativeLibrary.getInstance("kernel32"); var user = NativeLibrary.getInstance("user32");
        long bytes = Math.addExact(40L, Math.multiplyExact((long) image.getWidth() * image.getHeight(), 4));
        Pointer allocation = function(kernel, "GlobalAlloc").invokePointer(new Object[]{2, new BaseTSD.SIZE_T(bytes)});
        if (allocation == null) throw failure("分配图片剪贴板内存");
        boolean opened = false, transferred = false;
        try {
            Pointer data = function(kernel, "GlobalLock").invokePointer(new Object[]{allocation});
            if (data == null) throw failure("锁定图片剪贴板内存");
            try {
                writeDib(image, data);
            } finally { function(kernel, "GlobalUnlock").invokeInt(new Object[]{allocation}); }
            if (function(user, "OpenClipboard").invokeInt(new Object[]{new Pointer(owner)}) == 0) throw failure("打开图片剪贴板");
            opened = true;
            if (function(user, "EmptyClipboard").invokeInt(new Object[]{}) == 0) throw failure("清空图片剪贴板");
            if (function(user, "SetClipboardData").invokePointer(new Object[]{8, allocation}) == null) throw failure("写入图片剪贴板");
            transferred = true; // 成功后内存归系统所有，不能自行释放。
        } finally {
            if (opened && function(user, "CloseClipboard").invokeInt(new Object[]{}) == 0)
                com.mojang.logging.LogUtils.getLogger().warn("关闭图片剪贴板失败，系统错误码 {}", Native.getLastError());
            if (!transferred && function(kernel, "GlobalFree").invokePointer(new Object[]{allocation}) != null)
                com.mojang.logging.LogUtils.getLogger().warn("释放图片剪贴板内存失败，系统错误码 {}", Native.getLastError());
        }
    }
    /** 将高清 RGB 写入标准 DIB，无缩放、无重新截图；供原生剪贴板和字节级行为验证共用。 */
    static void writeDib(BufferedImage image, Pointer data) {
        // CF_DIB: BITMAPINFOHEADER + 自底向上的 32 位 BGRX 像素；接收应用可直接粘贴图片。
        data.clear(40); data.setInt(0, 40); data.setInt(4, image.getWidth()); data.setInt(8, image.getHeight());
        data.setShort(12, (short) 1); data.setShort(14, (short) 32);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, image.getHeight() - 1 - y, image.getWidth(), 1, row, 0, image.getWidth());
            data.write(40L + (long) y * row.length * 4, row, 0, row.length);
        }
    }
    private static Function function(NativeLibrary library, String name) { return library.getFunction(name, Function.ALT_CONVENTION); }
    private static IOException failure(String operation) { return new IOException(operation + "失败，系统错误码 " + Native.getLastError()); }
}
