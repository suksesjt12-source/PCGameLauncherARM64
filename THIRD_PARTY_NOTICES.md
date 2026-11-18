# Third-party notices

## Box64

Box64 is built from the upstream project at https://github.com/ptitSeb/box64
using the pinned source revision configured in `.github/workflows/android.yml`.

MIT License

Copyright (c) 2020 ptitSeb

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Runtime components and archive libraries

The optional in-app bootstrap downloads the Winlator Mali Bionic root filesystem
and Proton 9.0 x86_64 runtime from the published
https://github.com/GunaCharanTeja/Winlator-Extras release assets. The downloaded
Wine/Proton components remain separate runtime files and are not embedded in this
repository's source tree. Retain the component authors' license and copyright files
when redistributing installed runtime contents.

Winlator Mali source reference: https://github.com/GunaCharanTeja/WinlatorMali
(branch bionic-mali-1.2; MIT-licensed fork; the upstream Winlator code and
individual runtime components retain their own licenses).

## Zstandard and tar extraction

Zstandard JNI: https://github.com/luben/zstd-jni (BSD 2-Clause License).
Apache Commons Compress: https://commons.apache.org/proper/commons-compress/
(Apache License 2.0).

DXVK and other optional graphics components retain their respective upstream
licenses. Their license texts must accompany any redistributed component package.
