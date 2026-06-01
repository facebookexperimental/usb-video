using UnityEngine;

public static class UsbBridgeAPI
{
    private static AndroidJavaClass bridge;

    static UsbBridgeAPI()
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        bridge = new AndroidJavaClass("com.beanotherlab.usbvideounitybridge.UsbBridge");
#endif
    }

    public static string TestConnection()
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        return bridge.CallStatic<string>("testConnection");
#else
        return "EDITOR";
#endif
    }

    public static void PingUnity()
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        bridge.CallStatic("pingUnity");
#endif
    }
    
    public static void StartFakeStream()
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        bridge.CallStatic("startFakeStream");
#endif
    }
}