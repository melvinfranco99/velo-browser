"""Lista de dominios bloqueados (rastreadores, publicidad, malware)."""

from pathlib import Path

_IGNORED = {
    "0.0.0.0", "127.0.0.1", "localhost", "localhost.localdomain", "local",
    "broadcasthost", "ip6-localhost", "ip6-loopback", "ip6-localnet",
    "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
}


class Blocklist:
    """Conjunto de dominios; un dominio bloquea también todos sus subdominios."""

    def __init__(self, paths):
        self.domains = set()
        for path in paths:
            self._load(Path(path))

    def _load(self, path):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            return
        for line in text.splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            # Formato hosts ("0.0.0.0 dominio") o un dominio por línea.
            domain = parts[1] if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1") else parts[0]
            domain = domain.lower().rstrip(".")
            if domain and domain not in _IGNORED and "." in domain:
                self.domains.add(domain)

    def __len__(self):
        return len(self.domains)

    def is_blocked(self, host):
        labels = host.lower().rstrip(".").split(".")
        for i in range(len(labels) - 1):
            if ".".join(labels[i:]) in self.domains:
                return True
        return False
