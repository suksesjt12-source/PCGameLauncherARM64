#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>

static bool send_all(SOCKET socket_handle, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        const int count = send(socket_handle, data.data() + sent,
                               static_cast<int>(data.size() - sent), 0);
        if (count <= 0) return false;
        sent += static_cast<size_t>(count);
    }
    return true;
}

static bool read_line(SOCKET socket_handle, std::string& line) {
    line.clear();
    char character = 0;
    while (line.size() < 256) {
        const int count = recv(socket_handle, &character, 1, 0);
        if (count <= 0) return false;
        if (character == '\n') return true;
        if (character != '\r') line.push_back(character);
    }
    return false;
}

static void inject_key(int virtual_key, bool down) {
    if (virtual_key < 1 || virtual_key > 255) return;
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wVk = static_cast<WORD>(virtual_key);
    input.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
    SendInput(1, &input, sizeof(INPUT));
}

static void inject_mouse_button(int button, bool down) {
    DWORD flags = 0;
    if (button == 1) flags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
    if (button == 2) flags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
    if (button == 3) flags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
    if (flags == 0) return;
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dwFlags = flags;
    SendInput(1, &input, sizeof(INPUT));
}

static void inject_mouse_move(int dx, int dy) {
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dx = dx;
    input.mi.dy = dy;
    input.mi.dwFlags = MOUSEEVENTF_MOVE;
    SendInput(1, &input, sizeof(INPUT));
}

static void inject_mouse_wheel(int delta) {
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.mouseData = static_cast<DWORD>(delta);
    input.mi.dwFlags = MOUSEEVENTF_WHEEL;
    SendInput(1, &input, sizeof(INPUT));
}

static void handle_command(const std::string& line) {
    std::istringstream stream(line);
    std::string command;
    stream >> command;
    if (command == "K") {
        int key = 0, down = 0;
        if (stream >> key >> down) inject_key(key, down != 0);
    } else if (command == "B") {
        int button = 0, down = 0;
        if (stream >> button >> down) inject_mouse_button(button, down != 0);
    } else if (command == "M") {
        int dx = 0, dy = 0;
        if (stream >> dx >> dy) inject_mouse_move(dx, dy);
    } else if (command == "W") {
        int delta = 0;
        if (stream >> delta) inject_mouse_wheel(delta);
    }
}

int main(int argc, char** argv) {
    if (argc != 4) {
        std::cerr << "Usage: pcgl-input-bridge.exe host port token\n";
        return 2;
    }
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return 3;

    const std::string host = argv[1];
    const int port = std::atoi(argv[2]);
    const std::string token = argv[3];
    if (port < 1 || port > 65535 || token.size() != 48) {
        WSACleanup();
        return 4;
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<u_short>(port));
    if (InetPtonA(AF_INET, host.c_str(), &address.sin_addr) != 1) {
        WSACleanup();
        return 5;
    }

    SOCKET connection = INVALID_SOCKET;
    for (int attempt = 0; attempt < 120; ++attempt) {
        connection = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (connection != INVALID_SOCKET &&
            connect(connection, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0) break;
        if (connection != INVALID_SOCKET) closesocket(connection);
        connection = INVALID_SOCKET;
        Sleep(500);
    }
    if (connection == INVALID_SOCKET) {
        WSACleanup();
        return 6;
    }

    if (!send_all(connection, "AUTH " + token + "\n")) {
        closesocket(connection);
        WSACleanup();
        return 7;
    }
    std::string response;
    if (!read_line(connection, response) || response != "OK") {
        closesocket(connection);
        WSACleanup();
        return 8;
    }
    send_all(connection, "READY\n");

    std::string pending;
    char buffer[2048];
    while (true) {
        const int count = recv(connection, buffer, sizeof(buffer), 0);
        if (count <= 0) break;
        pending.append(buffer, static_cast<size_t>(count));
        if (pending.size() > 65536) {
            pending.clear();
            continue;
        }
        size_t newline = std::string::npos;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            if (line == "QUIT") {
                closesocket(connection);
                WSACleanup();
                return 0;
            }
            handle_command(line);
        }
    }
    closesocket(connection);
    WSACleanup();
    return 0;
}
