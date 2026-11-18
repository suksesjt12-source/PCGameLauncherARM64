#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <algorithm>
#include <cstdint>
#include <string>

namespace {
    constexpr UINT_PTR kTimerId = 1;
    constexpr UINT kTimerMs = 33;

    struct TestState {
        unsigned frame = 0;
        int clicks = 0;
        int mouseX = 0;
        int mouseY = 0;
        bool hasMouse = false;
        bool keyDown = false;
    };

    TestState g_state;

    COLORREF barColor(unsigned index, unsigned frame) {
        const unsigned r = (index * 67U + frame * 3U) & 0xffU;
        const unsigned g = (index * 31U + frame * 5U + 80U) & 0xffU;
        const unsigned b = (index * 97U + frame * 7U + 140U) & 0xffU;
        return RGB(r, g, b);
    }

    void fill(HDC dc, const RECT& rectangle, COLORREF color) {
        HBRUSH brush = CreateSolidBrush(color);
        if (brush) {
            FillRect(dc, &rectangle, brush);
            DeleteObject(brush);
        }
    }

    void drawText(HDC dc, const wchar_t* value, int x, int y, COLORREF color, int height = 22) {
        SetTextColor(dc, color);
        SetBkMode(dc, TRANSPARENT);
        HFONT font = CreateFontW(
            height, 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE,
            DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI"
        );
        HGDIOBJ previous = font ? SelectObject(dc, font) : nullptr;
        TextOutW(dc, x, y, value, static_cast<int>(lstrlenW(value)));
        if (previous) SelectObject(dc, previous);
        if (font) DeleteObject(font);
    }

    void paint(HWND window) {
        PAINTSTRUCT paintStruct{};
        HDC dc = BeginPaint(window, &paintStruct);
        RECT client{};
        GetClientRect(window, &client);
        const int width = client.right - client.left;
        const int height = client.bottom - client.top;

        fill(dc, client, RGB(13, 20, 34));
        RECT header{0, 0, width, 72};
        fill(dc, header, RGB(25, 42, 68));
        drawText(dc, L"PCGL - WINE WINDOW TEST", 22, 16, RGB(244, 248, 255), 26);
        drawText(dc, L"Win32 GDI drawing / timer / keyboard / mouse", 24, 47, RGB(177, 204, 232), 16);

        const int left = 22;
        const int top = 102;
        const int gap = 8;
        const int barHeight = 30;
        const int availableWidth = std::max(80, width - left * 2);
        for (unsigned index = 0; index < 8; ++index) {
            RECT bar{
                left,
                top + static_cast<int>(index) * (barHeight + gap),
                left + availableWidth,
                top + static_cast<int>(index) * (barHeight + gap) + barHeight
            };
            fill(dc, bar, barColor(index, g_state.frame));
            RECT inset{bar.left + 3, bar.top + 3, bar.right - 3, bar.bottom - 3};
            fill(dc, inset, RGB(18, 27, 43));
            RECT progress{
                inset.left,
                inset.top,
                inset.left + static_cast<int>((availableWidth - 6) *
                    ((g_state.frame + index * 13U) % 100U) / 100U),
                inset.bottom
            };
            fill(dc, progress, barColor(index, g_state.frame + 35U));
        }

        const int infoY = top + 8 * (barHeight + gap) + 8;
        wchar_t info[160]{};
        wsprintfW(info, L"Frames: %u    Mouse clicks: %d", g_state.frame, g_state.clicks);
        drawText(dc, info, 22, infoY, RGB(230, 237, 248), 19);

        if (g_state.hasMouse) {
            wsprintfW(info, L"Mouse position: %d, %d", g_state.mouseX, g_state.mouseY);
            drawText(dc, info, 22, infoY + 28, RGB(135, 210, 190), 18);
        } else {
            drawText(dc, L"Move the mouse / touch pad and click inside this window.", 22, infoY + 28,
                RGB(135, 210, 190), 18);
        }

        drawText(dc, L"Press ESC to close. A visible animated window confirms the software X11 path.",
            22, height - 34, RGB(190, 202, 221), 15);
        EndPaint(window, &paintStruct);
    }

    LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
        switch (message) {
            case WM_CREATE:
                SetTimer(window, kTimerId, kTimerMs, nullptr);
                return 0;
            case WM_TIMER:
                if (wParam == kTimerId) {
                    ++g_state.frame;
                    InvalidateRect(window, nullptr, FALSE);
                }
                return 0;
            case WM_PAINT:
                paint(window);
                return 0;
            case WM_ERASEBKGND:
                return 1;
            case WM_MOUSEMOVE:
                g_state.mouseX = GET_X_LPARAM(lParam);
                g_state.mouseY = GET_Y_LPARAM(lParam);
                g_state.hasMouse = true;
                InvalidateRect(window, nullptr, FALSE);
                return 0;
            case WM_LBUTTONDOWN:
                ++g_state.clicks;
                SetCapture(window);
                InvalidateRect(window, nullptr, FALSE);
                return 0;
            case WM_LBUTTONUP:
                ReleaseCapture();
                return 0;
            case WM_KEYDOWN:
                g_state.keyDown = true;
                if (wParam == VK_ESCAPE) {
                    DestroyWindow(window);
                    return 0;
                }
                InvalidateRect(window, nullptr, FALSE);
                return 0;
            case WM_KEYUP:
                g_state.keyDown = false;
                InvalidateRect(window, nullptr, FALSE);
                return 0;
            case WM_DESTROY:
                KillTimer(window, kTimerId);
                PostQuitMessage(0);
                return 0;
            default:
                return DefWindowProcW(window, message, wParam, lParam);
        }
    }
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int showCommand) {
    const wchar_t* className = L"PCGLWineDisplayTestWindow";
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.style = CS_HREDRAW | CS_VREDRAW;
    windowClass.lpfnWndProc = windowProc;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
    windowClass.hbrBackground = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
    windowClass.lpszClassName = className;
    if (!RegisterClassExW(&windowClass)) return 2;

    HWND window = CreateWindowExW(
        0, className, L"PCGL Wine Display Test",
        WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT, CW_USEDEFAULT, 760, 560,
        nullptr, nullptr, instance, nullptr
    );
    if (!window) return 3;

    ShowWindow(window, showCommand);
    UpdateWindow(window);
    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    UnregisterClassW(className, instance);
    return static_cast<int>(message.wParam);
}
