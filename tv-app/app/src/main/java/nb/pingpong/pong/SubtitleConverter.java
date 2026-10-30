package nb.pingpong.pong;

import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.ssa.SsaParser;
import androidx.media3.extractor.text.ttml.TtmlParser;
import androidx.media3.extractor.text.webvtt.WebvttParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Converts subtitle formats that can be converted reliably (ASS / SSA, WebVTT, TTML) to SubRip (.srt).
 * <p>
 * Parsing is done by the AndroidX Media3 extractor parsers (the same ones ExoPlayer uses); this class only
 * detects the format and writes the parsed cues out as SRT.
 * <p>
 * {@link #toSrt(byte[])} returns {@code null} whenever the file should be used as-is: it is already SRT,
 * it is a format we don't convert (MicroDVD .sub, bitmap subs, ...), it isn't valid UTF-8 / UTF-16, or parsing
 * failed. The caller then keeps the original file, so a converter problem can never lose subtitles.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SubtitleConverter {

    private static final int SNIFF_CHARS = 4096;
    private static final long FALLBACK_DURATION_US = 3_000_000L;

    private SubtitleConverter() {
    }

    private enum Format {ASS, VTT, TTML}

    /** @return SRT file bytes (UTF-8), or {@code null} if the input should be used unchanged. */
    @Nullable
    public static byte[] toSrt(byte[] raw) {
        try {
            byte[] utf8 = toUtf8(raw);
            if (utf8 == null) return null;
            Format format = detect(utf8);
            if (format == null) return null;

            SubtitleParser parser;
            switch (format) {
                case ASS:
                    parser = new SsaParser();
                    break;
                case VTT:
                    parser = new WebvttParser();
                    break;
                default:
                    parser = new TtmlParser();
                    break;
            }

            final List<CuesWithTiming> timed = new ArrayList<>();
            parser.parse(utf8, 0, utf8.length, SubtitleParser.OutputOptions.allCues(), timed::add);
            if (timed.isEmpty()) return null;

            Collections.sort(timed, new Comparator<CuesWithTiming>() {
                @Override
                public int compare(CuesWithTiming a, CuesWithTiming b) {
                    return Long.compare(a.startTimeUs, b.startTimeUs);
                }
            });

            StringBuilder out = new StringBuilder();
            int index = 0;
            for (CuesWithTiming t : timed) {
                StringBuilder text = new StringBuilder();
                for (Cue cue : t.cues) {
                    if (cue.text == null || cue.text.toString().trim().isEmpty()) continue;
                    String line = toSrtText(cue.text);
                    if (line.isEmpty()) continue;
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
                if (text.length() == 0) continue;

                long start = Math.max(0, t.startTimeUs);
                long end = t.durationUs > 0 ? start + t.durationUs : start + FALLBACK_DURATION_US;
                out.append(++index).append('\n')
                        .append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n')
                        .append(text).append("\n\n");
            }
            if (index == 0) return null;
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception | LinkageError e) {
            return null; // fall back to the original file
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Normalises the file to BOM-less UTF-8 bytes. Returns null if it isn't UTF-8 / UTF-16 text. */
    @Nullable
    private static byte[] toUtf8(byte[] raw) throws CharacterCodingException {
        if (raw.length >= 2) {
            int b0 = raw[0] & 0xFF, b1 = raw[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE).getBytes(StandardCharsets.UTF_8);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE).getBytes(StandardCharsets.UTF_8);
            }
        }
        int offset = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer body = ByteBuffer.wrap(raw, offset, raw.length - offset);
        try {
            // Strict check: legacy 8-bit encodings are left to VLC's own charset detection.
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(body.duplicate());
        } catch (CharacterCodingException e) {
            return null;
        }
        byte[] out = new byte[raw.length - offset];
        System.arraycopy(raw, offset, out, 0, out.length);
        return out;
    }

    @Nullable
    private static Format detect(byte[] utf8) {
        String head = new String(utf8, 0, Math.min(utf8.length, SNIFF_CHARS * 2), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("webvtt")) return Format.VTT;
        if (head.contains("[script info]") || head.contains("[events]") || head.contains("[v4+ styles]")
                || head.contains("[v4 styles]")) {
            return Format.ASS;
        }
        if ((head.startsWith("<?xml") || head.startsWith("<tt")) && head.contains("<tt")) return Format.TTML;
        return null; // SRT, MicroDVD, unknown: leave untouched
    }

    /** Plain text plus bold / italic tags, which SRT players (including VLC) understand. */
    private static String toSrtText(CharSequence cs) {
        String s = cs.toString().replace("\r", "");
        SparseArray<StringBuilder> opens = new SparseArray<>();
        SparseArray<StringBuilder> closes = new SparseArray<>();
        if (cs instanceof Spanned) {
            Spanned sp = (Spanned) cs;
            for (StyleSpan span : sp.getSpans(0, s.length(), StyleSpan.class)) {
                int start = sp.getSpanStart(span), end = sp.getSpanEnd(span);
                if (start < 0 || end <= start) continue;
                int style = span.getStyle();
                boolean bold = (style & android.graphics.Typeface.BOLD) != 0;
                boolean italic = (style & android.graphics.Typeface.ITALIC) != 0;
                if (bold) {
                    append(opens, start, "<b>");
                    append(closes, end, "</b>");
                }
                if (italic) {
                    append(opens, start, "<i>");
                    append(closes, end, "</i>");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            StringBuilder c = closes.get(i);
            if (c != null) sb.append(c);
            StringBuilder o = opens.get(i);
            if (o != null) sb.append(o);
            if (i < s.length()) sb.append(s.charAt(i));
        }
        // A blank line would end the cue in SRT, so collapse them.
        return sb.toString().replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    private static void append(SparseArray<StringBuilder> map, int pos, String tag) {
        StringBuilder sb = map.get(pos);
        if (sb == null) {
            sb = new StringBuilder();
            map.put(pos, sb);
        }
        sb.append(tag);
    }

    private static String timestamp(long us) {
        long ms = us / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d",
                ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }
}
