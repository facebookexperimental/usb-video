using UnityEngine;

public class UsbBridgeListener : MonoBehaviour
{
    private Texture2D tex;
    private int textureId;
    private AndroidJavaClass bridgeClass;
    private bool isTextureInitialized = false;

    void Start()
    {
        Debug.Log("Initializing SurfaceTexture Pipeline...");

        // 1. Setup target buffer resolution matching your UVC stream
        tex = new Texture2D(1920, 1080, TextureFormat.RGBA32, false);
        tex.filterMode = FilterMode.Bilinear;
        tex.Apply(); 

        // 2. Fetch the OpenGLES texture tracking ID
        textureId = tex.GetNativeTexturePtr().ToInt32();

        // 3. Apply the custom URP OES shader material to your quad/mesh
        GetComponent<Renderer>().material.mainTexture = tex;

        // 4. Initialize the Android plugin and hand over the ID
        bridgeClass = new AndroidJavaClass("com.beanotherlab.usbvideounitybridge.UsbBridge");
        bridgeClass.CallStatic("createSurfaceTexture", textureId);
        
        isTextureInitialized = true;
    }

    void Update()
    {
        // 5. Pull incoming C++ frames into the visible Unity material context
        if (isTextureInitialized && bridgeClass != null)
        {
            bridgeClass.CallStatic("updateTexture");
        }
    }
}