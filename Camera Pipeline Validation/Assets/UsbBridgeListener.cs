using UnityEngine;
using System;

public class UsbBridgeListener : MonoBehaviour
{
    public Renderer targetRenderer;

    private Texture2D tex;
    private int textureId;

    private AndroidJavaClass bridgeClass;
    private IntPtr nativeRenderEventFunc;

    private bool initialized;

    void Start()
    {
        Debug.Log("bladibo");

        // 1. Create Unity texture (GPU owned by Unity)
        tex = new Texture2D(
            1920,
            1080,
            TextureFormat.RGBA32,
            false,
            true
        );

        tex.Apply();

        targetRenderer.material.mainTexture = tex;

        textureId = tex.GetNativeTexturePtr().ToInt32();

        Debug.Log("Unity texture id = " + textureId);

        // 2. Bind Android bridge
        bridgeClass = new AndroidJavaClass(
            "com.beanotherlab.usbvideounitybridge.UsbBridge"
        );

        bridgeClass.CallStatic("create", textureId);
        Debug.Log("[UNITY] create() called");

        initialized = true;
    }

    void Update()
    {
        if (!initialized) return;

        // 3. Render thread trigger (Saki pattern)
        GL.IssuePluginEvent(GetRenderEventFunc(), 1);
    }

    private IntPtr GetRenderEventFunc()
    {
        if (nativeRenderEventFunc == IntPtr.Zero)
        {
            nativeRenderEventFunc =
                NativePlugin.GetRenderEventFunc(); // from plugin
        }
        return nativeRenderEventFunc;
    }
    
}