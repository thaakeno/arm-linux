#!/usr/bin/env python3
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
producer = root / "src/backends/anland/display_producer.c"
backend = root / "src/backends/anland/anland_backend.cpp"
egl = root / "src/backends/anland/anland_egl_backend.cpp"

def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"expected text not found in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))

replace_once(
    producer,
    "#define HANDSHAKE_TIMEOUT_MS 100",
    "#define HANDSHAKE_TIMEOUT_MS 5000",
)

text = backend.read_text()
if "#include <QCoreApplication>" not in text:
    text = text.replace("#include <QFutureWatcher>\n", "#include <QCoreApplication>\n#include <QFutureWatcher>\n", 1)

timer_block = """    m_reconnectTimer = new QTimer(this);
    m_reconnectTimer->setInterval(s_reconnectIntervalMs);
    connect(m_reconnectTimer, &QTimer::timeout, this, &AnlandBackend::onReconnectTimer);
"""
if timer_block not in text:
    raise SystemExit("reconnect timer block not found")
text = text.replace(timer_block, "", 1)

startup_old = """    // connect_to_deamon() only fetched screen info; the context is still in
    // fallback with no consumer fds or dmabufs. Enter fallback explicitly so the
    // reconnect timer starts and discovers the consumer via try_exit_fallback().
    enterFallback();
"""
startup_new = """    // Vessel owns the Android consumer and starts it before KWin. Establish the
    // complete fd/dmabuf transport as a required part of compositor initialization.
    // There is no startup fallback/reconnect mode: if the native transport cannot be
    // established, fail the compositor visibly instead of rendering a black session.
    if (try_exit_fallback(m_display) != 0) {
        qCCritical(KWIN_ANLAND) << "Vessel display transport handshake failed";
        return false;
    }
    m_inFallback = false;
    m_consumerReady = false;
    setupNotifiers();
    anland_audio_set_fd(get_audio_fd(m_display));
    qCInfo(KWIN_ANLAND) << "Vessel display transport ready with"
                        << get_buf_count(m_display) << "buffers";
"""
if startup_old not in text:
    raise SystemExit("startup fallback block not found")
text = text.replace(startup_old, startup_new, 1)

start = text.find("void AnlandBackend::enterFallback()")
end = text.find("// ---------------------------------------------------------------------------\n// Clipboard sync", start)
if start < 0 or end < 0:
    raise SystemExit("fallback function region not found")
direct_failure = """void AnlandBackend::enterFallback()
{
    if (m_inFallback) {
        return;
    }

    // Vessel deliberately has no compositor fallback path. A broken native
    // transport is a session failure, not a reason to keep KWin alive on an
    // invisible software/placeholder output.
    m_inFallback = true;
    qCCritical(KWIN_ANLAND) << "Vessel display transport disconnected; terminating compositor";

    if (m_inputDevice) {
        m_inputDevice->touchCancel();
    }
    teardownNotifiers();
    anland_audio_set_fd(-1);
    anland_camera_clear();
    QCoreApplication::exit(74);
}

void AnlandBackend::onReconnectTimer()
{
    // Intentionally unused in Vessel. Transport loss is fatal and never enters a
    // reconnect/fallback rendering mode.
}

"""
text = text[:start] + direct_failure + text[end:]
backend.write_text(text)

text = egl.read_text()
old_add = """    auto layer = std::make_unique<AnlandEglLayer>(anlandOutput, this);
    // Let AnlandBackend reach this layer through its output (output->eglLayer()).
    anlandOutput->setEglLayer(layer.get());
    m_outputs[output] = std::move(layer);
"""
new_add = """    auto layer = std::make_unique<AnlandEglLayer>(anlandOutput, this);

    // The transport is mandatory and already complete before EGL is created.
    // Import the consumer dmabufs now, once, instead of waiting for a fallback
    // reconnect timer to populate the render targets later.
    const int bufferCount = get_buf_count(m_backend->display());
    if (is_fallback(m_backend->display()) || bufferCount <= 0 || !layer->importBuffers(bufferCount)) {
        setFailed("Vessel display transport has no importable dmabuf set");
        return;
    }

    // Let AnlandBackend reach this layer through its output (output->eglLayer()).
    anlandOutput->setEglLayer(layer.get());
    m_outputs[output] = std::move(layer);
"""
if old_add not in text:
    raise SystemExit("EGL addOutput block not found")
text = text.replace(old_add, new_add, 1)

old_begin = """std::optional<OutputLayerBeginFrameInfo> AnlandEglLayer::doBeginFrame()
{
    m_backend->openglContext()->makeCurrent();

    m_currentIndex = get_selected_idx(m_display);

    return OutputLayerBeginFrameInfo{
        .renderTarget = *m_renderTargets[m_currentIndex],
        .repaint = m_accumDamage[m_currentIndex],
    };
}
"""
new_begin = """std::optional<OutputLayerBeginFrameInfo> AnlandEglLayer::doBeginFrame()
{
    m_backend->openglContext()->makeCurrent();

    if (m_bufCount <= 0) {
        qCCritical(KWIN_ANLAND) << "Vessel render requested without imported buffers";
        return std::nullopt;
    }
    m_currentIndex = get_selected_idx(m_display);
    if (m_currentIndex < 0 || m_currentIndex >= m_bufCount || !m_renderTargets[m_currentIndex]) {
        qCCritical(KWIN_ANLAND) << "Vessel selected invalid render buffer" << m_currentIndex;
        return std::nullopt;
    }

    return OutputLayerBeginFrameInfo{
        .renderTarget = *m_renderTargets[m_currentIndex],
        .repaint = m_accumDamage[m_currentIndex],
    };
}
"""
if old_begin not in text:
    raise SystemExit("EGL begin-frame block not found")
text = text.replace(old_begin, new_begin, 1)
egl.write_text(text)
