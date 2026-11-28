package dev.jun.gyeot;

import java.util.ArrayList;
import java.util.List;

/**
 * What runs in the app now, by the names the user knows: installing an update replaces the app and stops all of it, so
 * the update asks first and can wait until it ends (MainActivity.installUpdate). Main thread.
 */
final class RunningWork {
    private RunningWork() {}
    static List<String> names(AppStore store) {
        List<String> out = new ArrayList<>();
        if (store.voiceActive) out.add("음성 대화");
        if (store.recording) out.add("녹음");
        if (!store.workLive.isEmpty()) out.add("작업창 작업");
        if (store.tasks.pendingCount() > 0) out.add("휴대폰 작업");
        if (BrowserEngine.anyTaskRunning()) out.add("브라우저 작업");
        if (VideoAskPanel.anyRunning()) out.add("마이튜브 요약·대본");
        if (MyTubeActivity.mp3Running) out.add("MP3 만들기");
        if (LabRunner.runningNow()) out.add("연구실 연구");
        if (ProjectMaker.anyRunning()) out.add("프로젝트 만들기");
        if (LinuxRuntime.busy) out.add("앱 안 리눅스 설치");
        return out;
    }
}
