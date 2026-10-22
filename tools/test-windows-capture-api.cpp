// This test library exports discovery only, without graphics hooks.
extern "C" __declspec(dllexport) int RENDERDOC_GetAPI(int,void*){return 0;}
