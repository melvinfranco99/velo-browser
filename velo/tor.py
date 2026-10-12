"""Instancia privada de Tor lanzada y controlada por Velo.

Velo no depende del servicio de sistema (que requiere root): arranca su propio
proceso `tor` como tu usuario, en puertos aleatorios de 127.0.0.1, con
autenticación por cookie en el puerto de control. El proceso muere si Velo
muere (__OwningControllerProcess).
"""

import os
import re
import shutil
import socket

from PyQt6.QtCore import QObject, QProcess, QTimer, pyqtSignal

BOOTSTRAP_TIMEOUT_MS = 180_000
_BOOTSTRAP_RE = re.compile(r"Bootstrapped (\d+)%[^:]*: (.*)")


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class TorProcess(QObject):
    progress = pyqtSignal(int, str)
    ready = pyqtSignal()
    failed = pyqtSignal(str)

    def __init__(self, socks_port, control_port, data_dir, extra_torrc=None, parent=None):
        super().__init__(parent)
        self.socks_port = socks_port
        self.control_port = control_port
        self.data_dir = data_dir
        self.extra_torrc = extra_torrc
        self.proc = None
        self._done = False
        self._last_problem = ""
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._on_timeout)

    def start(self):
        self._done = False
        tor = shutil.which("tor")
        if not tor:
            self._fail("No se encontró el programa 'tor'.\nInstálalo con:  sudo pacman -S tor")
            return
        self.data_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.data_dir, 0o700)

        lines = [
            # IsolateDestAddr: cada destino distinto usa su propio circuito.
            f"SocksPort 127.0.0.1:{self.socks_port} IsolateDestAddr",
            f"ControlPort 127.0.0.1:{self.control_port}",
            "CookieAuthentication 1",
            f"DataDirectory {self.data_dir}",
            "ClientOnly 1",
            "AvoidDiskWrites 1",
            "SafeLogging 1",
            "Log notice stdout",
            f"__OwningControllerProcess {os.getpid()}",
        ]
        if self.extra_torrc and self.extra_torrc.is_file():
            # Permite añadir puentes (bridges) u otras opciones del usuario.
            lines.append(self.extra_torrc.read_text(encoding="utf-8"))
        torrc = self.data_dir / "torrc"
        torrc.write_text("\n".join(lines) + "\n", encoding="utf-8")

        self.proc = QProcess(self)
        self.proc.setProcessChannelMode(QProcess.ProcessChannelMode.MergedChannels)
        self.proc.readyReadStandardOutput.connect(self._on_output)
        self.proc.finished.connect(self._on_finished)
        self.proc.errorOccurred.connect(self._on_error)
        # --defaults-torrc /dev/null: ignora la configuración del sistema.
        self.proc.start(tor, ["--defaults-torrc", "/dev/null", "-f", str(torrc)])
        self._timer.start(BOOTSTRAP_TIMEOUT_MS)

    def _on_output(self):
        data = bytes(self.proc.readAllStandardOutput()).decode("utf-8", errors="replace")
        for line in data.splitlines():
            if "[warn]" in line or "[err]" in line:
                self._last_problem = line.split("]", 1)[-1].strip()
            m = _BOOTSTRAP_RE.search(line)
            if m and not self._done:
                pct = int(m.group(1))
                self.progress.emit(pct, m.group(2).strip())
                if pct >= 100:
                    self._done = True
                    self._timer.stop()
                    self.ready.emit()

    def _on_finished(self, code, _status):
        self._timer.stop()
        if not self._done:
            self._fail(f"Tor se cerró durante el arranque (código {code}).\n{self._last_problem}")
        else:
            self.failed.emit("El proceso de Tor terminó. Las conexiones quedan bloqueadas.")

    def _on_error(self, _error):
        if not self._done:
            self._fail(f"No se pudo ejecutar Tor: {self.proc.errorString()}")

    def _on_timeout(self):
        self._fail("Tor no terminó de conectarse a tiempo.\n"
                   f"{self._last_problem}\n\nSi tu red censura Tor, añade puentes en "
                   "~/.config/velo/torrc-extra")

    def _fail(self, message):
        self._done = True
        self.stop()
        self.failed.emit(message)

    def control(self, *commands):
        """Envía comandos al puerto de control autenticándose con la cookie."""
        cookie = (self.data_dir / "control_auth_cookie").read_bytes().hex()
        with socket.create_connection(("127.0.0.1", self.control_port), timeout=5) as s:
            f = s.makefile("rwb")
            for cmd in (f"AUTHENTICATE {cookie}", *commands, "QUIT"):
                f.write(cmd.encode() + b"\r\n")
                f.flush()
                reply = f.readline().decode(errors="replace").strip()
                if not reply.startswith("250"):
                    raise RuntimeError(reply or "sin respuesta del puerto de control")

    def new_identity(self):
        """Pide a Tor circuitos nuevos para las próximas conexiones."""
        self.control("SIGNAL NEWNYM")

    def stop(self):
        if self.proc and self.proc.state() != QProcess.ProcessState.NotRunning:
            try:
                self.proc.finished.disconnect(self._on_finished)
            except TypeError:
                pass
            self.proc.terminate()
            if not self.proc.waitForFinished(5000):
                self.proc.kill()
                self.proc.waitForFinished(2000)
