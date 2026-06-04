using System;
using System.Runtime.InteropServices;

public static class NativePlugin
{
    [DllImport("usbvideo")]
    public static extern IntPtr GetRenderEventFunc();
}