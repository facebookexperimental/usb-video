Shader "Custom/URP_AndroidOES"
{
    Properties
    {
        _MainTex ("Texture (Android OES)", 2D) = "white" {}
    }
    SubShader
    {
        Tags 
        { 
            "RenderPipeline" = "UniversalPipeline" 
            "RenderType" = "Opaque" 
            "Queue" = "Geometry" 
        }
        LOD 100

        Pass
        {
            Name "ForwardLit"
            Tags { "LightMode" = "UniversalForward" }

            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            
            #pragma prefer_hlslcc gles
            #pragma exclude_renderers d3d11_9x

            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"

            struct Attributes
            {
                float4 positionOS   : POSITION;
                float2 uv           : TEXCOORD0;
            };

            struct Varyings
            {
                float4 positionCS   : SV_POSITION;
                float2 uv           : TEXCOORD0;
            };

            // Android Mobile OpenGLES Platform Definition
            #if defined(SHADER_API_GLES3) || defined(SHADER_API_GLES)
                // Use the legacy sampler type which allows internal redirection to OES hardware textures
                sampler2D _MainTex;
            // Desktop Editor Platform Definition
            #else
                TEXTURE2D(_MainTex);
                SAMPLER(sampler_MainTex);
            #endif

            Varyings vert(Attributes input)
            {
                Varyings output;
                output.positionCS = TransformObjectToHClip(input.positionOS.xyz);
                
                #if defined(SHADER_API_GLES3) || defined(SHADER_API_GLES)
                    output.uv = float2(input.uv.x, 1.0 - input.uv.y); // Flip UV for Android
                #else
                    output.uv = input.uv;
                #endif
                return output;
            }

            half4 frag(Varyings input) : SV_Target
            {
                #if defined(SHADER_API_GLES3) || defined(SHADER_API_GLES)
                    // Mobile: Sample the unified legacy sampler object
                    return tex2D(_MainTex, input.uv); 
                #else
                    // Editor: Sample using standard modern split macros
                    return SAMPLE_TEXTURE2D(_MainTex, sampler_MainTex, input.uv);
                #endif
            }
            ENDHLSL
        }
    }
    FallBack "Universal Render Pipeline/Lit"
}
