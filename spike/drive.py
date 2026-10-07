"""Drive the spike REPL through a real pty and render the screen with pyte.

Needs pyte (pip install pyte). Example, from this directory with a server on 7888:

    python3 drive.py bb . -- bb jline_repl.clj 7888
"""
import fcntl, os, pty, select, struct, sys, termios, time
import pyte

COLS, ROWS = 110, 40
ENTER, TAB, CC, CD, UP = "\r", "\t", "\x03", "\x04", "\x1b[A"
CX_CD = "\x18\x04"


class Term:
    def __init__(self, argv, cwd):
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)
        pid, fd = pty.fork()
        if pid == 0:
            fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
            os.chdir(cwd)
            os.environ["TERM"] = "xterm-256color"
            os.execvp(argv[0], argv)
        self.pid, self.fd = pid, fd
        # answer cursor position / device attribute queries like a real terminal
        self.screen.write_process_input = lambda data: os.write(fd, data.encode())
        self.exit = None

    def pump(self, seconds):
        end = time.time() + seconds
        while (left := end - time.time()) > 0:
            r, _, _ = select.select([self.fd], [], [], left)
            if not r:
                continue
            try:
                data = os.read(self.fd, 65536)
            except OSError:
                return
            if not data:
                return
            self.stream.feed(data)

    def wait_for(self, text, timeout=60):
        end = time.time() + timeout
        while time.time() < end:
            if text in self.text():
                return True
            self.pump(0.1)
        return False

    def send(self, keys, settle=0.6):
        os.write(self.fd, keys.encode())
        self.pump(settle)

    def text(self):
        return "\n".join(l.rstrip() for l in self.screen.display).rstrip()

    def styled_line(self, y):
        """Line y with non-default colors marked as [color|text] and bold as *."""
        out, cur, buf = [], None, ""
        row = self.screen.buffer[y]
        for x in range(COLS):
            ch = row[x]
            key = (ch.fg, ch.bold)
            if key != cur:
                if buf:
                    out.append(buf if cur == ("default", False) else f"[{cur[0]}{'*' if cur[1] else ''}|{buf}]")
                cur, buf = key, ""
            buf += ch.data
        if buf:
            out.append(buf if cur == ("default", False) else f"[{cur[0]}{'*' if cur[1] else ''}|{buf}]")
        return "".join(out).rstrip()

    def snap(self, title, last=6, colors=False):
        lines = [l.rstrip() for l in self.screen.display]
        while lines and not lines[-1]:
            lines.pop()
        start = max(0, len(lines) - last)
        print(f"--- {title} (cursor at row {self.screen.cursor.y}, col {self.screen.cursor.x})")
        for y in range(start, len(lines)):
            print("  | " + (self.styled_line(y) if colors else lines[y]))

    def wait_exit(self, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            self.pump(0.1)
            pid, status = os.waitpid(self.pid, os.WNOHANG)
            if pid:
                self.exit = os.waitstatus_to_exitcode(status)
                return self.exit
        return None


def scenario(t):
    if not t.wait_for("user=>", timeout=90):
        t.snap("never got a prompt", last=40)
        return
    t.snap("startup", last=6)

    t.send("(+ 1" + ENTER)
    t.snap("Enter on an incomplete form continues the input", last=2)
    t.send("2)" + ENTER, settle=1)
    t.snap("completed multi-line form evaluates", last=4)

    t.send('(def s #"r+" "hi" :kw 42 \\a nil #_x) ; note', settle=0.8)
    t.snap("highlighting while typing", last=1, colors=True)
    t.send(ENTER, settle=1)

    t.send("(map-i")
    t.send(TAB, settle=1.2)
    t.snap("TAB completion", last=2)
    t.send(CC)

    t.send("(map ", settle=1)
    t.snap("eldoc as tail tip", last=1, colors=True)
    t.send(CX_CD, settle=1.2)
    t.snap("C-x C-d prints doc above the prompt, buffer kept", last=8)
    t.send(CC)

    t.send('(future (Thread/sleep 1500) (println "late output"))' + ENTER, settle=1)
    t.send("(+ 40", settle=2.5)
    t.snap("async output lands above the prompt, partial input kept", last=4)
    t.send(" 2)" + ENTER, settle=1)
    t.snap("and the kept input still evaluates", last=3)

    t.send("(Thread/sleep 60000)" + ENTER, settle=1)
    t.send(CC, settle=2)
    t.snap("Ctrl-C during eval interrupts it", last=4)

    t.send("(read-line)" + ENTER, settle=1.2)
    t.send("hello (unbalanced" + ENTER, settle=1.2)
    t.snap("need-input reads a raw line from the terminal", last=3)

    t.send('(str "日本語" " é")' + ENTER, settle=1)
    t.snap("wide characters", last=2)

    t.send("\x10", settle=0.8)
    t.snap("history: C-p recalls the previous input", last=1)
    t.send(CC)
    t.send("\x1bOA", settle=0.8)
    t.snap("history: Up (ESC O A) recalls the previous input", last=1)
    t.send(CC)

    t.send(CD)
    print(f"--- Ctrl-D exit code: {t.wait_exit()}")


if __name__ == "__main__":
    label, cwd = sys.argv[1], sys.argv[2]
    argv = sys.argv[sys.argv.index("--") + 1:]
    print(f"===== {label}: {' '.join(argv)}")
    scenario(Term(argv, cwd))
