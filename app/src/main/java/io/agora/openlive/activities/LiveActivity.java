package io.agora.openlive.activities;


import static io.agora.rtc.video.VideoCanvas.RENDER_MODE_HIDDEN;
import static io.agora.rtc.video.VideoEncoderConfiguration.STANDARD_BITRATE;
import static io.agora.rtc.video.VideoEncoderConfiguration.VD_640x360;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telecom.Call;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.agora.openlive.AgoraApplication;
import io.agora.openlive.R;
import io.agora.openlive.stats.LocalStatsData;
import io.agora.openlive.stats.RemoteStatsData;
import io.agora.openlive.stats.StatsData;
import io.agora.openlive.ui.VideoGridContainer;
import io.agora.rtc.Constants;
import io.agora.rtc.IRtcEngineEventHandler;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.live.LiveTranscoding;
import io.agora.rtc.models.ChannelMediaOptions;
import io.agora.rtc.video.BeautyOptions;
import io.agora.rtc.video.VideoCanvas;
import io.agora.rtc.video.VideoEncoderConfiguration;

public class LiveActivity extends RtcBaseActivity {
    private static final String TAG = LiveActivity.class.getSimpleName();

    private VideoGridContainer mVideoGridContainer;
    private ImageView mMuteAudioBtn;
    private ImageView mMuteVideoBtn;
    private Button publish;
    private EditText mEtUrl;
    private VideoEncoderConfiguration.VideoDimensions mVideoDimension;
    private LiveTranscoding transcoding;
    private VideoEncoderConfiguration.VideoDimensions dimensions = VD_640x360;
    private final int MAXUserCount = 2;
    List<Integer> users = new ArrayList<>();

    private LiveTranscoding.TranscodingUser localTranscodingUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_room);
        initUI();
        initData();
    }

    private void initUI() {
        TextView roomName = findViewById(R.id.live_room_name);
        roomName.setText(config().getChannelName());
        roomName.setSelected(true);

        initUserIcon();
        int role = getIntent().getIntExtra(
                io.agora.openlive.Constants.KEY_CLIENT_ROLE,
                Constants.CLIENT_ROLE_BROADCASTER);
        boolean isBroadcaster = (role == Constants.CLIENT_ROLE_BROADCASTER);

        mMuteVideoBtn = findViewById(R.id.live_btn_mute_video);
        mMuteVideoBtn.setActivated(isBroadcaster);
        mEtUrl = findViewById(R.id.et_url);
        publish = findViewById(R.id.btn_publish);
        mMuteAudioBtn = findViewById(R.id.live_btn_mute_audio);
        mMuteAudioBtn.setActivated(isBroadcaster);

        ImageView beautyBtn = findViewById(R.id.live_btn_beautification);
        beautyBtn.setActivated(true);
        rtcEngine().setBeautyEffectOptions(beautyBtn.isActivated(),
                new BeautyOptions());

        publish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPublish(true);
            }
        });
        mVideoGridContainer = findViewById(R.id.live_video_grid_layout);
        mVideoGridContainer.setStatsManager(statsManager());

        rtcEngine().setClientRole(role);
        if (isBroadcaster) startBroadcast();
        rtcEngine().enableDualStreamMode(true);
//        joinChannel(config().getChannelName());
    }


    AsyncTask retryTask;

    private void startPublish(boolean encode) {
        if (encode) {

            transcoding = new LiveTranscoding();
            transcoding.width = dimensions.width;
            transcoding.height = dimensions.height;
            localTranscodingUser = new LiveTranscoding.TranscodingUser();
            localTranscodingUser.x = 0;
            localTranscodingUser.y = 0;
            localTranscodingUser.width = transcoding.width / MAXUserCount;
            localTranscodingUser.height = transcoding.height / MAXUserCount;
            localTranscodingUser.uid = config().getUid();
            int lastX = 0;
            int lastY = 0;
            int ret = transcoding.addUser(localTranscodingUser);
            for (int i = 0; i < users.size(); i++) {
                if (users.get(i).intValue()==config().getUid()) continue;
                LiveTranscoding.TranscodingUser remoteUser = new LiveTranscoding.TranscodingUser();
                remoteUser.x = lastX+(transcoding.width / MAXUserCount)>transcoding.width?0:lastX+(transcoding.width / MAXUserCount);
                remoteUser.y = lastX+(transcoding.width / MAXUserCount)>transcoding.width?lastY+(transcoding.height / MAXUserCount):lastY;
                lastX = remoteUser.x;
                lastY = remoteUser.y;
                remoteUser.width = transcoding.width / MAXUserCount;
                remoteUser.height = transcoding.height / MAXUserCount;
                remoteUser.uid = users.get(i);
                transcoding.addUser(remoteUser);
            }
//            rtcEngine().setLiveTranscoding(transcoding);
            int code = rtcEngine().startRtmpStreamWithTranscoding(mEtUrl.getText().toString(), transcoding);
            //       rtcEngine().addPublishStreamUrl(mEtUrl.getText().toString(), true);

            if (code == 0) {
                retryTask = new AsyncTask() {
                    @Override
                    protected Object doInBackground(Object[] objects) {
                        Integer result = null;
                        for (int i = 0; i < 2; i++) {
                            try {
                                Thread.sleep(6 * 1000);
                            } catch (InterruptedException e) {
                                Log.e(TAG, e.getMessage());
                                break;
                            }
                            int code = rtcEngine().startRtmpStreamWithTranscoding(mEtUrl.getText().toString(), transcoding);
                        }
                        return result;
                    }
                };
                retryTask.execute();
            }

        } else {
            rtcEngine().startRtmpStreamWithoutTranscoding(mEtUrl.getText().toString());
        }
        /**Prevent repeated entry*/
        publish.setEnabled(false);

    }

    boolean unpublishing = false;

    private void stopPublish() {
        /**Removes an RTMP stream from the CDN.
         * This method removes the RTMP URL address (added by addPublishStreamUrl) from a CDN live
         * stream. The SDK reports the result of this method call in the onRtmpStreamingStateChanged callback.
         * @param url The RTMP URL address to be removed. The maximum length of this parameter is
         *            111124 bytes. The URL address must not contain special characters, such as
         *            Chinese language characters.
         * @return
         *   config().getUid(): Success.
         *   <config().getUid(): Failure.
         * PS:
         *   Ensure that you enable the RTMP Converter service before using this function. See
         *      Prerequisites in Push Streams to CDN.
         *   Ensure that the user joins a channel before calling this method.
         *   This method applies to Live Broadcast only.
         *   This method removes only one stream RTMP URL address each time it is called.*/
        retryTask.cancel(true);
        int ret = rtcEngine().removePublishStreamUrl(mEtUrl.getText().toString());
    }

    private void initUserIcon() {
        Bitmap origin = BitmapFactory.decodeResource(getResources(), R.drawable.fake_user_icon);
        RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(getResources(), origin);
        drawable.setCircular(true);
        ImageView iconView = findViewById(R.id.live_name_board_icon);
        iconView.setImageDrawable(drawable);
    }

    private void initData() {
        mVideoDimension = io.agora.openlive.Constants.VIDEO_DIMENSIONS[
                config().getVideoDimenIndex()];
    }

    @Override
    protected void onGlobalLayoutCompleted() {

        ViewGroup topLayout = findViewById(R.id.live_room_top_layout);
        ViewGroup.LayoutParams params = topLayout.getLayoutParams();
        params.height = mStatusBarHeight + topLayout.getMeasuredHeight();
        topLayout.setLayoutParams(params);
        topLayout.setPadding(0, mStatusBarHeight, 0, 0);
    }

    private void startBroadcast() {
        rtcEngine().setClientRole(Constants.CLIENT_ROLE_BROADCASTER);
        SurfaceView surface = prepareRtcVideo(config().getUid(), true);
        mVideoGridContainer.addUserVideoSurface(config().getUid(), surface, true);
        mMuteAudioBtn.setActivated(true);
    }

    private void stopBroadcast() {
        rtcEngine().setClientRole(Constants.CLIENT_ROLE_AUDIENCE);
        removeRtcVideo(config().getUid(), true);
        mVideoGridContainer.removeUserVideo(config().getUid(), true);
        mMuteAudioBtn.setActivated(false);
    }

    @Override
    public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
        // Do nothing at the moment
        users.add(uid);
    }

    @Override
    public void onUserJoined(int uid, int elapsed) {
        // Do nothing at the moment
        Log.e(TAG, "uid:" + uid);
        users.add(uid);
    }

    @Override
    public void onUserOffline(final int uid, int reason) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                removeRemoteUser(uid);
                users.remove(uid);
            }
        });
    }

    @Override
    public void onFirstRemoteVideoDecoded(final int uid, int width, int height, int elapsed) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                renderRemoteUser(uid);
            }
        });
    }

    private void renderRemoteUser(int uid) {
        SurfaceView surface = prepareRtcVideo(uid, false);
        mVideoGridContainer.addUserVideoSurface(uid, surface, false);
    }

    private void removeRemoteUser(int uid) {
        removeRtcVideo(uid, false);
        mVideoGridContainer.removeUserVideo(uid, false);
    }

    @Override
    public void onLocalVideoStats(IRtcEngineEventHandler.LocalVideoStats stats) {
        if (!statsManager().isEnabled()) return;

        LocalStatsData data = (LocalStatsData) statsManager().getStatsData(config().getUid());
        if (data == null) return;

        data.setWidth(mVideoDimension.width);
        data.setHeight(mVideoDimension.height);
        data.setFramerate(stats.sentFrameRate);
    }

    @Override
    public void onRtcStats(IRtcEngineEventHandler.RtcStats stats) {
        if (!statsManager().isEnabled()) return;

        LocalStatsData data = (LocalStatsData) statsManager().getStatsData(config().getUid());
        if (data == null) return;

        data.setLastMileDelay(stats.lastmileDelay);
        data.setVideoSendBitrate(stats.txVideoKBitRate);
        data.setVideoRecvBitrate(stats.rxVideoKBitRate);
        data.setAudioSendBitrate(stats.txAudioKBitRate);
        data.setAudioRecvBitrate(stats.rxAudioKBitRate);
        data.setCpuApp(stats.cpuAppUsage);
        data.setCpuTotal(stats.cpuAppUsage);
        data.setSendLoss(stats.txPacketLossRate);
        data.setRecvLoss(stats.rxPacketLossRate);
    }

    @Override
    public void onNetworkQuality(int uid, int txQuality, int rxQuality) {
        if (!statsManager().isEnabled()) return;

        StatsData data = statsManager().getStatsData(uid);
        if (data == null) return;

        data.setSendQuality(statsManager().qualityToString(txQuality));
        data.setRecvQuality(statsManager().qualityToString(rxQuality));
    }

    @Override
    public void onRemoteVideoStats(IRtcEngineEventHandler.RemoteVideoStats stats) {
        if (!statsManager().isEnabled()) return;

        RemoteStatsData data = (RemoteStatsData) statsManager().getStatsData(stats.uid);
        if (data == null) return;

        data.setWidth(stats.width);
        data.setHeight(stats.height);
        data.setFramerate(stats.rendererOutputFrameRate);
        data.setVideoDelay(stats.delay);
    }

    @Override
    public void onRemoteAudioStats(IRtcEngineEventHandler.RemoteAudioStats stats) {
        if (!statsManager().isEnabled()) return;

        RemoteStatsData data = (RemoteStatsData) statsManager().getStatsData(stats.uid);
        if (data == null) return;

        data.setAudioNetDelay(stats.networkTransportDelay);
        data.setAudioNetJitter(stats.jitterBufferDelay);
        data.setAudioLoss(stats.audioLossRate);
        data.setAudioQuality(statsManager().qualityToString(stats.quality));
    }

    @Override
    public void onRtmpStreamingStateChanged(String url, int state, int errCode) {
        Log.e(TAG, url + "state:" + state + "code:" + errCode);
    }

    @Override
    public void onRtmpStreamingEvent(String url, int error) {
        Log.e(TAG, url + "code:" + error);
    }

    @Override
    public void finish() {
        super.finish();
        stopBroadcast();
        statsManager().clearAllData();
    }

    public void onLeaveClicked(View view) {
        finish();
    }

    public void onSwitchCameraClicked(View view) {
        rtcEngine().switchCamera();
    }

    public void onBeautyClicked(View view) {
        view.setActivated(!view.isActivated());
        rtcEngine().setBeautyEffectOptions(view.isActivated(),
                new BeautyOptions());
    }


    public void onMoreClicked(View view) {
        // Do nothing at the moment
    }

    public void onPushStreamClicked(View view) {
        // Do nothing at the moment
    }

    public void onMuteAudioClicked(View view) {
        if (!mMuteVideoBtn.isActivated()) return;

        rtcEngine().muteLocalAudioStream(view.isActivated());
        view.setActivated(!view.isActivated());
    }

    public void onMuteVideoClicked(View view) {
        if (view.isActivated()) {
            stopBroadcast();
        } else {
            startBroadcast();
        }
        view.setActivated(!view.isActivated());
    }


}
