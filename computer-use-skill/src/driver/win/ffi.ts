/**
 * driver/win/ffi.ts —— Windows API FFI 唯一加载点（ADR-2：koffi）
 * 硬规则：FFI 只允许出现在 src/driver/win/ 内（file_structure.md）。
 * 所有函数指针/回调在此集中声明，参数经白名单校验后才传入（security_strategy §4）。
 */
import koffi from "koffi";

const user32 = koffi.load("user32.dll");
const gdi32 = koffi.load("gdi32.dll");
const gdiplus = koffi.load("gdiplus.dll");

// ---- 类型（必须在 func 声明之前全部注册，koffi 按名字解析） ----
export const HWND = koffi.pointer("HWND", koffi.opaque());
export const HDC = koffi.pointer("HDC", koffi.opaque());
export const HBITMAP = koffi.pointer("HBITMAP", koffi.opaque());
export const WNDENUMPROC = koffi.proto("WNDENUMPROC", "bool", [HWND, "longlong"]);
koffi.struct("RECT", { left: "long", top: "long", right: "long", bottom: "long" });
koffi.struct("GdiplusStartupInput", { GdiplusVersion: "uint32", Dummy: "uint64" });
const GUIDType = koffi.struct("GUID", {
  Data1: "uint32",
  Data2: "uint16",
  Data3: "uint16",
  Data4: koffi.array("uint8", 8),
});

// ---- user32：窗口枚举与信息 ----
export const EnumWindows = user32.func("bool __stdcall EnumWindows(WNDENUMPROC* cb, longlong lp)");
export const IsWindowVisible = user32.func("bool __stdcall IsWindowVisible(HWND hwnd)");
/** 文本出参用 uint16 缓冲区（调用方 Buffer.alloc + utf16le 解码） */
export const GetWindowTextW = user32.func("int __stdcall GetWindowTextW(HWND hwnd, _Out_ uint16_t* buf, int max)");
export const GetClassNameW = user32.func("int __stdcall GetClassNameW(HWND hwnd, _Out_ uint16_t* buf, int max)");
export const GetWindowThreadProcessId = user32.func("uint32 __stdcall GetWindowThreadProcessId(HWND hwnd, _Out_ uint32* pid)");
export const GetClientRect = user32.func("bool __stdcall GetClientRect(HWND hwnd, _Out_ RECT* rect)");
export const IsIconic = user32.func("bool __stdcall IsIconic(HWND hwnd)");
/** SW_RESTORE=9：最小化窗口先还原再截图/交互 */
export const ShowWindow = user32.func("bool __stdcall ShowWindow(HWND hwnd, int cmd)");
export const PrintWindow = user32.func("bool __stdcall PrintWindow(HWND hwnd, HDC hdc, uint32 flags)");
export const SetProcessDPIAware = user32.func("bool __stdcall SetProcessDPIAware()");
export const GetForegroundWindow = user32.func("HWND __stdcall GetForegroundWindow()");
export const SetForegroundWindow = user32.func("bool __stdcall SetForegroundWindow(HWND hwnd)");
export const SetCursorPos = user32.func("bool __stdcall SetCursorPos(int x, int y)");
koffi.struct("POINT", { x: "long", y: "long" });
export const GetCursorPos = user32.func("bool __stdcall GetCursorPos(_Out_ POINT* pt)");
// ---- 升级v2 层2：PostMessage 后台执行（ADR-003，须在 POINT 注册之后）----
export const PostMessageW = user32.func("bool __stdcall PostMessageW(HWND hwnd, uint32 msg, uintptr_t wparam, intptr_t lparam)");
export const SendMessageW = user32.func("intptr_t __stdcall SendMessageW(HWND hwnd, uint32 msg, uintptr_t wparam, intptr_t lparam)");
export const ClientToScreen = user32.func("bool __stdcall ClientToScreen(HWND hwnd, _Inout_ POINT* pt)");
export const ScreenToClient = user32.func("bool __stdcall ScreenToClient(HWND hwnd, _Inout_ POINT* pt)");
// 简化输入注入：legacy mouse_event/keybd_event（原型简单，行为与 SendInput 等价）
export const mouse_event = user32.func(
  "void __stdcall mouse_event(uint32 flags, uint32 dx, uint32 dy, uint32 data, void* extra)"
);
export const keybd_event = user32.func(
  "void __stdcall keybd_event(uint8 vk, uint8 scan, uint32 flags, void* extra)"
);

// ---- gdi32：位图 ----
export const CreateCompatibleDC = gdi32.func("HDC __stdcall CreateCompatibleDC(HDC hdc)");
export const CreateCompatibleBitmap = gdi32.func("HBITMAP __stdcall CreateCompatibleBitmap(HDC hdc, int w, int h)");
export const SelectObject = gdi32.func("void* __stdcall SelectObject(HDC hdc, void* obj)");
export const DeleteObject = gdi32.func("bool __stdcall DeleteObject(void* obj)");
export const DeleteDC = gdi32.func("bool __stdcall DeleteDC(HDC hdc)");
export const GetDC = user32.func("HDC __stdcall GetDC(HWND hwnd)");
export const ReleaseDC = user32.func("int __stdcall ReleaseDC(HWND hwnd, HDC hdc)");

// ---- gdiplus：PNG 编码（GdipSaveImageToFile，免第三方图片库） ----
export const GdiplusStartup = gdiplus.func(
  "int __stdcall GdiplusStartup(_Out_ void** token, GdiplusStartupInput* input, void* _Reserved)"
);
export const GdipCreateBitmapFromHBITMAP = gdiplus.func(
  "int __stdcall GdipCreateBitmapFromHBITMAP(HBITMAP hbm, void* hpal, _Out_ void** bitmap)"
);
export const GdipSaveImageToFile = gdiplus.func(
  "int __stdcall GdipSaveImageToFile(void* bitmap, str16 path, GUID* clsid, void* params)"
);
export const GdipDisposeImage = gdiplus.func("int __stdcall GdipDisposeImage(void* image)");

// PNG encoder CLSID：{557CF406-1A04-11D3-9A73-0000F81EF32E}
// koffi 的 struct* 参数可直接接收 JS 对象（自动分配），无需显式构造
export const PNG_CLSID: Record<string, unknown> = {
  Data1: 0x557cf406,
  Data2: 0x1a04,
  Data3: 0x11d3,
  Data4: [0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e],
};
void GUIDType;

let gdiplusReady = false;
/** 初始化 GDI+（幂等）+ DPI 感知（坑点预案：DPI 坐标错位） */
export function initWinFfi(): void {
  if (gdiplusReady) return;
  SetProcessDPIAware();
  const token = [null];
  const input = { GdiplusVersion: 1, Dummy: 0n };
  const rc = GdiplusStartup(token, input, null);
  if (rc !== 0) throw new Error(`GdiplusStartup failed: ${rc}`);
  gdiplusReady = true;
}
