package app.volna;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class FramingTest {
    private static void verify(int tag, int[] sizes, int seed) {
        Random random = new Random(seed);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        List<byte[]> expected = new ArrayList<>();
        for (int words : sizes) {
            int header = tag == 0xefefefef && words < 127 ? 1 : 4;
            byte[] frame = new byte[header + words * 4];
            random.nextBytes(frame);
            if (tag == 0xefefefef) {
                frame[0] = (byte)(header == 1 ? words : 127);
                if (header == 4) for (int j=0; j<3; j++) frame[1+j] = (byte)(words >>> (8*j));
            } else {
                for (int j=0; j<4; j++) frame[j] = (byte)((words*4) >>> (8*j));
            }
            input.write(frame,0,frame.length);
            for (int j=0; j<frame.length; j++) frame[j] ^= 0x55;
            expected.add(frame);
        }
        TgWebMsgSplitter splitter = new TgWebMsgSplitter(tag);
        byte[] bytes = input.toByteArray();
        List<byte[]> actual = new ArrayList<>();
        for (int at=0; at<bytes.length;) {
            int end = Math.min(bytes.length, at + (seed==0 ? 1 : random.nextInt(4096)+1));
            byte[] plain = Arrays.copyOfRange(bytes,at,end), encrypted = plain.clone();
            for (int j=0;j<encrypted.length;j++) encrypted[j] ^= 0x55;
            actual.addAll(splitter.split(encrypted,plain));
            at=end;
        }
        if (actual.size()!=expected.size() || !splitter.flush().isEmpty()) throw new AssertionError();
        for (int i=0;i<actual.size();i++) if (!Arrays.equals(actual.get(i),expected.get(i))) throw new AssertionError();
    }

    public static void main(String[] args) {
        for (int tag : new int[]{0xdddddddd,0xeeeeeeee,0xefefefef})
            for (int seed=0;seed<20;seed++) verify(tag,new int[]{1,126,127,5,700,4},seed);
        TgWebMsgSplitter invalid = new TgWebMsgSplitter(0xdddddddd);
        invalid.split(new byte[]{1},new byte[]{0});
        List<byte[]> parts = invalid.split(new byte[]{2,3,4,5},new byte[]{0,0,0,0});
        if (parts.size()!=2 || parts.get(0)[0]!=1 || parts.get(1)[3]!=5) throw new AssertionError();
        TgWebMsgSplitter tail = new TgWebMsgSplitter(0xeeeeeeee);
        tail.split(new byte[]{11,12},new byte[]{32,0});
        if (!Arrays.equals(tail.flush().get(0),new byte[]{11,12})) throw new AssertionError();
    }
}
