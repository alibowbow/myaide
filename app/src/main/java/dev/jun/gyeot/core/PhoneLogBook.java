package dev.jun.gyeot.core;

import java.util.*;
/** Memory-only diagnostic log. A clear affects its observed prefix, not later diagnostic events. */
public final class PhoneLogBook {
    public static final long UNDO_MS = 300_000;
    /** Every voice tool call and reply is logged now (VoiceToolLog), so a call takes more lines than it did. */
    private static final int LIMIT = 200;
    private record Entry(long sequence, String text) {}
    public static final class Snapshot {
        public final String id = UUID.randomUUID().toString();
        public final long generation, through;
        public final int count;
        private Snapshot(long generation,long through,int count) { this.generation=generation;this.through=through;this.count=count; }
    }
    public static final class Receipt {
        public final String operation, snapshot;
        public final long through, generation;
        public final int cleared;
        private boolean restored;
        private Receipt(String operation,Snapshot snapshot,long generation,int cleared) {
            this.operation=operation;this.snapshot=snapshot.id;this.through=snapshot.through;this.generation=generation;this.cleared=cleared;
        }
    }
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final LinkedHashMap<String,Receipt> receipts = new LinkedHashMap<>();
    private long sequence,generation;
    private Receipt undo;
    private ArrayList<Entry> removed = new ArrayList<>();
    private long undoUntil;
    public synchronized void add(String text) { entries.add(new Entry(++sequence,text)); trim(); }
    private void trim() { while(entries.size()>LIMIT) entries.remove(0); }
    public synchronized String text() { return entries.stream().map(Entry::text).collect(java.util.stream.Collectors.joining("\n")); }
    public synchronized Snapshot snapshot() { return new Snapshot(generation,sequence,entries.size()); }
    public synchronized Receipt receipt(String operation) { return receipts.get(operation); }
    public synchronized Receipt clear(String operation,Snapshot snapshot,long now) {
        if(operation==null||operation.isBlank()||snapshot==null) throw new IllegalArgumentException("기록 정리 대상을 확인하지 못했어요.");
        Receipt prior=receipts.get(operation); if(prior!=null) return prior;
        if(snapshot.generation!=generation) throw new IllegalStateException("기록 상태가 바뀌어 이전 정리 요청을 취소했어요.");
        ArrayList<Entry> taken=new ArrayList<>();
        entries.removeIf(e->{if(e.sequence<=snapshot.through){taken.add(e);return true;}return false;});
        Receipt receipt=new Receipt(operation,snapshot,++generation,taken.size());
        receipts.put(operation,receipt);while(receipts.size()>32)receipts.remove(receipts.keySet().iterator().next());
        undo=receipt;removed=taken;undoUntil=now+UNDO_MS;
        return receipt;
    }
    public synchronized boolean verified(Receipt receipt) {
        return receipt!=null && !receipt.restored && receipts.get(receipt.operation)==receipt && entries.stream().noneMatch(e->e.sequence<=receipt.through);
    }
    public synchronized long undoRemaining(Receipt receipt,long now) { expire(now);return undo==receipt&&!removed.isEmpty()?Math.max(0,undoUntil-now):0; }
    public synchronized boolean canUndo(long now) { expire(now);return undo!=null&&!removed.isEmpty(); }
    public synchronized int undo(long now) {
        expire(now);if(undo==null)return 0;
        int count=removed.size();entries.addAll(removed);entries.sort(Comparator.comparingLong(Entry::sequence));trim();
        generation++;undo.restored=true;removed.clear();undo=null;return count;
    }
    private void expire(long now) { if(undo!=null&&now>=undoUntil){removed.clear();undo=null;} }
}
