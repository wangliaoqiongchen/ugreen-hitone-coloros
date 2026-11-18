package com.mo.ugreenprotocolhook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模块自带的 BLE 发送器 —— 让系统控制不再依赖测试 APP。
 *
 * ================== 为什么需要它 ==================
 *
 * 原本"发 BLE 命令"这件事由测试 APP 的前台服务负责，
 * 于是出现两个问题：
 *   · 卸载 APP 后，系统里的降噪入口就失效了
 *   · APP 被系统杀掉时也会失效
 *
 * 现在把这件事搬进模块：模块运行在系统应用进程里
 * （设备空间 / 无线耳机），这些进程本来就长期存活，
 * 而且它们有蓝牙权限，可以直接连耳机。
 *
 * ================== 连接策略：短连接 ==================
 *
 * 每次发命令：连接 → 服务发现 → 写入 → 断开。
 *
 * 为什么不用长连接：
 *   设备空间和无线耳机是【两个进程】，
 *   如果各自维持一条常连接，会互相抢耳机（BLE 外设连接数有限）。
 *   短连接用完就放，任何一方随时都能用，互不干扰。
 *
 * 代价是每次约 1~2 秒的延迟 —— 对"切换降噪"这个操作可以接受。
 *
 * ================== 协议参数（真机验证过） ==================
 *
 *     设备 MAC       90:A0:BE:2D:5E:CA
 *     Service        66666666-6666-6666-6666-666666666666
 *     命令特征        77777777-7777-7777-7777-777777777777
 *     命令帧         8 字节，如 AA BB CC 09 01 A1 61 EA（降噪深度）
 */
final class T6BleSender {

    private static final String TAG = "UGREEN-BLE";

    private static final String TARGET_MAC = T6Config.mac();

    private static final UUID CMD_CHARACTERISTIC =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final UUID CMD_SERVICE =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    /** 整体超时（连接 + 服务发现 + 写入）。 */
    private static final long TIMEOUT_MS = 12000L;

    /** 同一时刻只允许一个发送流程，避免自己和自己抢。 */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    /** 发送结果回调。 */
    interface Result {
        void onResult(boolean success, String detail);
    }

    private T6BleSender() {
    }

    /**
     * 异步发送一条命令。
     *
     * 立即返回（true = 已开始尝试）。
     * 真正的结果通过 callback 异步通知，
     * 调用方可以在失败时回退到其它方案（例如广播给测试 APP）。
     */
    static boolean send(final Context ctx, final byte[] command,
                        final String label, final Result callback) {

        if (ctx == null || command == null) {
            return false;
        }

        if (!BUSY.compareAndSet(false, true)) {
            Log.w(TAG, "上一次发送还没结束，忽略本次：" + label);
            if (callback != null) {
                callback.onResult(false, "busy");
            }
            return false;
        }

        Log.i(TAG, "开始发送 " + label + "，命令=" + toHex(command));

        new Thread(() -> doSend(ctx, command, label, callback),
                "ugreen-ble-send").start();

        return true;
    }

    // ------------------------------------------------------------------

    private static void doSend(Context ctx, byte[] command,
                               String label, Result callback) {

        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);

        BluetoothGatt[] gattRef = new BluetoothGatt[1];

        // 超时兜底：无论如何都要释放 BUSY，否则后续发送全被挡住
        final Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                Log.w(TAG, "超时，放弃本次发送：" + label);
                closeQuietly(gattRef[0]);
                BUSY.set(false);
                if (callback != null) {
                    callback.onResult(false, "timeout");
                }
            }
        };

        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                int newState) {
                Log.i(TAG, "连接状态变化 status=" + status + " newState=" + newState);

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "已连接，开始服务发现");
                    try {
                        gatt.discoverServices();
                    } catch (Throwable t) {
                        Log.w(TAG, "discoverServices 失败", t);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (finished.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout);
                        BUSY.set(false);
                        if (callback != null) {
                            callback.onResult(false, "disconnected");
                        }
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "服务发现失败 status=" + status);
                    failAndClose(gatt, finished, main, timeout, callback, "services_fail");
                    return;
                }

                BluetoothGattCharacteristic ch = findCommandCharacteristic(gatt);
                if (ch == null) {
                    Log.w(TAG, "找不到命令特征");
                    failAndClose(gatt, finished, main, timeout, callback, "no_characteristic");
                    return;
                }

                boolean ok = writeCommand(gatt, ch, command);
                Log.i(TAG, "写入结果=" + ok);

                if (!ok) {
                    failAndClose(gatt, finished, main, timeout, callback, "write_fail");
                    return;
                }

                /*
                 * 写入成功。
                 *
                 * 这里不等 onCharacteristicWrite 回调判断，
                 * 因为该特征可能用 WRITE_NO_RESPONSE（没有回调）。
                 * 只等一小会儿让链路把数据送出去，然后断开。
                 *
                 * 这个等待时间直接计入用户感知的延迟，
                 * 所以从 400ms 压到 150ms —— 实测足够。
                 */
                main.postDelayed(() -> {
                    if (finished.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout);
                        closeQuietly(gatt);
                        BUSY.set(false);
                        Log.i(TAG, "发送完成并断开：" + label);
                        if (callback != null) {
                            callback.onResult(true, "ok");
                        }
                    }
                }, 150L);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                Log.i(TAG, "onCharacteristicWrite status=" + status);
            }
        };

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                BUSY.set(false);
                if (callback != null) {
                    callback.onResult(false, "no_adapter");
                }
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(TARGET_MAC);

            main.postDelayed(timeout, TIMEOUT_MS);

            /*
             * autoConnect=false：单次直连，快。
             * 我们本来就只连一小会儿，不需要系统级别的持续重连。
             */
            BluetoothGatt gatt = device.connectGatt(
                    ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

            gattRef[0] = gatt;

            if (gatt == null) {
                main.removeCallbacks(timeout);
                BUSY.set(false);
                if (callback != null) {
                    callback.onResult(false, "connectGatt_null");
                }
            }

        } catch (Throwable t) {
            Log.w(TAG, "发送流程异常", t);
            BUSY.set(false);
            if (callback != null) {
                callback.onResult(false, "exception:" + t);
            }
        }
    }

    /** 找命令特征：先按 Service+特征精确找，找不到就全局找同名特征。 */
    private static BluetoothGattCharacteristic findCommandCharacteristic(
            BluetoothGatt gatt) {

        try {
            BluetoothGattService svc = gatt.getService(CMD_SERVICE);
            if (svc != null) {
                BluetoothGattCharacteristic ch =
                        svc.getCharacteristic(CMD_CHARACTERISTIC);
                if (ch != null) {
                    return ch;
                }
            }
            // 退路：遍历所有服务找同名特征
            for (BluetoothGattService s : gatt.getServices()) {
                BluetoothGattCharacteristic ch = s.getCharacteristic(CMD_CHARACTERISTIC);
                if (ch != null) {
                    return ch;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "查找特征异常", t);
        }
        return null;
    }

    /**
     * 写入命令 —— 兼容不同 API 版本。
     *
     * Android 13（TIRAMISU）起，写特征改为
     * writeCharacteristic(char, value, writeType)；
     * 之前要先 setValue 再 writeCharacteristic(char)。
     */
    private static boolean writeCommand(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic ch,
                                        byte[] data) {
        try {
            int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int r = gatt.writeCharacteristic(ch, data, writeType);
                return r == 0;   // BluetoothStatusCodes.SUCCESS == 0
            }

            ch.setWriteType(writeType);
            ch.setValue(data);
            return gatt.writeCharacteristic(ch);

        } catch (Throwable t) {
            Log.w(TAG, "写入异常", t);
            return false;
        }
    }

    private static void failAndClose(BluetoothGatt gatt, AtomicBoolean finished,
                                     Handler main, Runnable timeout,
                                     Result callback, String reason) {
        if (finished.compareAndSet(false, true)) {
            main.removeCallbacks(timeout);
            closeQuietly(gatt);
            BUSY.set(false);
            if (callback != null) {
                callback.onResult(false, reason);
            }
        }
    }

    private static void closeQuietly(BluetoothGatt gatt) {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
