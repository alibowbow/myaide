package dev.jun.gyeot;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import dev.jun.gyeot.core.FileSearchPlan;

/** Read-only local metadata search. Scope and result identities never come from a model. */
final class FileExplorerSearch {
    static final int MAX_ITEMS = 2000, MAX_DEPTH = 20;
    static final long TIMEOUT_NANOS = 60_000_000_000L;
    record Result(List<FileExplorerRepository.Entry> entries, int visited, boolean partial, String notice) {}
    private record Folder(String id, int depth) {}
    private FileExplorerSearch() {}

    static Result search(FileExplorerRepository repository, String startFolderId, FileSearchPlan plan, BooleanSupplier cancelled) throws Exception {
        return search(repository, startFolderId, plan, cancelled, System::nanoTime);
    }

    /** The clock seam makes the bound testable without sleeping. A slow provider query may finish after the deadline. */
    static Result search(FileExplorerRepository repository, String startFolderId, FileSearchPlan plan, BooleanSupplier cancelled, LongSupplier clock) throws Exception {
        Objects.requireNonNull(plan); check(cancelled);
        long started = clock.getAsLong();
        Deque<Folder> pending = new ArrayDeque<>(); pending.add(new Folder(startFolderId, 0));
        Set<String> folders = new HashSet<>(), files = new HashSet<>();
        List<FileExplorerRepository.Entry> result = new ArrayList<>();
        boolean partial = false; int visited = 0, inaccessible = 0;
        search: while (!pending.isEmpty()) {
            check(cancelled);
            if (visited >= MAX_ITEMS || clock.getAsLong() - started >= TIMEOUT_NANOS) { partial = true; break; }
            Folder folder = pending.removeFirst(); if (!folders.add(folder.id())) continue;
            // Public volumes can filter or deny individual subfolders independently of AI/SAF grants.
            // A SAF revocation or a denied starting folder still aborts; never report it as empty.
            List<FileExplorerRepository.Entry> entries;
            try {
                repository.requireMetadataAccess(folder.id());entries=repository.list(folder.id(),cancelled);
                partial|=repository.metadataPartial();
            } catch(InterruptedIOException stopped){throw stopped;}
            catch(IOException|SecurityException denied){
                if(!repository.publicMetadata()||folder.id().equals(startFolderId))throw denied;
                partial=true;inaccessible++;continue;
            }
            for (FileExplorerRepository.Entry entry : entries) {
                check(cancelled);
                if (visited >= MAX_ITEMS || clock.getAsLong() - started >= TIMEOUT_NANOS) { partial = true; break search; }
                visited++;
                if (entry.id().equals("primary:들퍼레/.들퍼레-휴지통") || entry.id().startsWith("primary:들퍼레/.들퍼레-휴지통/")) continue; // Never recommend a recovery copy as a redundant original.
                if (entry.directory()) {
                    if (folder.depth() < MAX_DEPTH) pending.addLast(new Folder(entry.id(), folder.depth() + 1));
                    else partial = true;
                } else if (files.add(entry.id()) && plan.matches(entry.name(), entry.mime(), entry.modified())) result.add(entry);
            }
        }
        if (plan.duplicateCandidates) {
            Map<Long, Integer> sizes = new HashMap<>();
            for (FileExplorerRepository.Entry entry : result) if (entry.size() >= 0) sizes.merge(entry.size(), 1, Integer::sum);
            result.removeIf(entry -> entry.size() < 0 || sizes.getOrDefault(entry.size(), 0) < 2);
        }
        Comparator<FileExplorerRepository.Entry> names = Comparator.comparing(FileExplorerRepository.Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(FileExplorerRepository.Entry::id);
        Comparator<FileExplorerRepository.Entry> order = switch (plan.sort) {
            case "newest" -> Comparator.comparingLong((FileExplorerRepository.Entry e) -> e.modified() > 0 ? e.modified() : Long.MIN_VALUE).reversed();
            case "oldest" -> Comparator.comparingLong((FileExplorerRepository.Entry e) -> e.modified() > 0 ? e.modified() : Long.MAX_VALUE);
            case "largest" -> Comparator.comparingLong(FileExplorerRepository.Entry::size).reversed();
            default -> names;
        };
        result.sort(order.thenComparing(names));
        String notice = (repository.publicMetadata()
            ? "Android가 현재 표시 가능한 항목에서 검색했어요. 운영체제가 숨기거나 차단한 항목은 포함되지 않을 수 있어요. 파일 종류는 확장자 기준이며 내용은 읽지 않았어요."+(partial?" 일부 경로 제외 또는 2,000개/20단계/60초 한도로 부분 검색했어요.":"")+(inaccessible>0?" 접근할 수 없는 하위 폴더 "+inaccessible+"개를 건너뛰었어요.":"")
            : partial ? "부분 검색 · 2,000개/20단계/60초 한도에 도달했어요. 더 작은 폴더에서 다시 찾아 주세요." : "선택한 폴더 안의 목록 검색 완료.")
            + (plan.hasDateFilter() ? " 실제 받은 날짜는 확인할 수 없어 수정 날짜 기준이에요. 날짜 미상 파일은 제외했어요." : "")
            + (plan.duplicateCandidates ? " 중복 후보는 크기만 같아요. 파일 내용은 읽지 않았으며 동일 파일로 확정하지 않았어요." : "");
        check(cancelled);repository.requireMetadataAccess(startFolderId);
        return new Result(List.copyOf(result), visited, partial, notice);
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("검색을 취소했어요. 파일은 변경하지 않았어요.");
    }
}
