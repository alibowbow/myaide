package dev.jun.gyeot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Fixed no-overwrite primitive only. Caller owns pinned parents, scope, identity and consent checks. */
final class TrashNative {
    private TrashNative() {}
    private static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("gyeot_trash"); loaded = true; }
        catch (UnsatisfiedLinkError | SecurityException unavailable) { loaded = false; }
        AVAILABLE = loaded;
    }
    static void moveNoReplace(int sourceParentFd, String sourceName, int targetParentFd, String targetName) throws IOException {
        if (sourceParentFd < 0 || targetParentFd < 0) throw new IOException("안전한 폴더 핸들이 필요해요.");
        byte[] source = basename(sourceName), target = basename(targetName);
        if (!AVAILABLE) throw new IOException("이 기기에서 안전한 휴지통 이동을 사용할 수 없어요. 원본은 변경하지 않았어요.");
        try { moveNoReplaceNative(sourceParentFd, source, targetParentFd, target); }
        catch (UnsatisfiedLinkError unavailable) { throw new IOException("안전한 이동 기능을 불러오지 못했어요. 원본은 변경하지 않았어요.", unavailable); }
    }
    private static byte[] basename(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.chars().anyMatch(Character::isISOControl))
            throw new IOException("안전하지 않은 파일 이름이에요.");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));
            if (bytes.remaining() > 255) throw new IOException("파일 이름이 너무 길어요.");
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException invalid) { throw new IOException("파일 이름 인코딩을 확인하지 못했어요.", invalid); }
    }
    private static native void moveNoReplaceNative(int sourceParentFd, byte[] sourceName, int targetParentFd, byte[] targetName) throws IOException;
}
