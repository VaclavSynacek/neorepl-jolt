/* A single editor thread owns this bridge and every Readline call.
 * During evaluation drain type-ahead in batches, always inspecting Ctrl-C,
 * even when the bounded queue is full. No second stdin reader or mutex. */
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <termios.h>
#include <unistd.h>

#define READY 1
#define INTERRUPT 2
#define END_INPUT 4
#define OVERFLOW 8

static struct termios original;
static int installed, reading, ended;
static unsigned char queued[4096];
static size_t head, count;

int neorepl_bridge_version(void) { return 2; }

static int configure(void) {
  struct termios mode;
  if (tcgetattr(0, &mode) != 0) return -1;
  mode.c_lflag &= ~(ICANON | ECHO);
  /* Preserve the distinction between Enter (CR) and Ctrl-J (LF), including
   * type-ahead captured while evaluating, and keep UTF-8 bytes intact. */
  mode.c_iflag &= ~(ICRNL | INLCR | IGNCR | ISTRIP | IXON);
  /* Disable only the interrupt character, not all terminal signals. */
  mode.c_cc[VINTR] = _POSIX_VDISABLE;
  mode.c_cc[VMIN] = 1;
  mode.c_cc[VTIME] = 0;
  return tcsetattr(0, TCSANOW, &mode);
}
int neorepl_terminal_install(void) {
  if (installed || tcgetattr(0, &original) != 0) return -1;
  if (configure() != 0) return -1;
  installed = 1;
  head = count = reading = ended = 0;
  return 0;
}
void neorepl_terminal_restore(void) {
  if (installed) (void)tcsetattr(0, TCSANOW, &original);
  installed = 0;
}
int neorepl_readline_prepare(void) {
  struct termios mode;
  if (!installed || tcgetattr(0, &mode) != 0) return -1;
  /* Readline decides whether to render from ECHO at prep time. Expose the
   * original echo setting briefly; Readline itself disables it before input. */
  mode.c_lflag = (mode.c_lflag & ~ECHO) | (original.c_lflag & ECHO);
  mode.c_cc[VINTR] = _POSIX_VDISABLE;
  return tcsetattr(0, TCSANOW, &mode);
}
int neorepl_input_mode(int active) {
  reading = active;
  /* Readline's prep_terminal may restore VINTR; disable it again. */
  return installed ? configure() : 0;
}
int neorepl_input_poll(int timeout_ms) {
  if (reading && (count || ended)) return READY;
  struct pollfd input = {0, POLLIN, 0};
  int n;
  do { n = poll(&input, 1, timeout_ms); } while (n < 0 && errno == EINTR);
  if (n <= 0) return n;
  if (reading) return READY;
  int flags = 0;
  /* Cap work per turn so output/close commands cannot be starved by a paste. */
  for (size_t drained = 0; drained < 65536; ) {
    unsigned char bytes[1024];
    ssize_t length = read(0, bytes, sizeof bytes);
    if (length <= 0) {
      if (length == 0 || (errno != EINTR && errno != EAGAIN)) {
        ended = 1;
        flags |= END_INPUT;
      }
      break;
    }
    drained += (size_t)length;
    for (ssize_t i = 0; i < length; ++i) {
      if (bytes[i] == 3) {
        flags |= INTERRUPT;
        /* Cancellation also cancels old type-ahead, never replaying a pasted
         * command after the interrupted evaluation. Bytes after ^C survive. */
        head = count = 0;
      } else if (count < sizeof queued) {
        queued[(head + count) % sizeof queued] = bytes[i];
        ++count;
      } else flags |= OVERFLOW;
    }
    input.revents = 0;
    if (poll(&input, 1, 0) <= 0) break;
  }
  return flags;
}
int neorepl_read_char(FILE *ignored) {
  (void)ignored;
  if (count) {
    unsigned char c = queued[head];
    head = (head + 1) % sizeof queued;
    --count;
    return c;
  }
  if (ended) return EOF;
  unsigned char c;
  ssize_t n;
  do { n = read(0, &c, 1); } while (n < 0 && errno == EINTR);
  return n == 1 ? c : EOF;
}
