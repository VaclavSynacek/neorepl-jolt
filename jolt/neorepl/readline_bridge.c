/* Keep Ctrl-C as an input byte, not an asynchronous managed-runtime signal.
 * While evaluating, preserve other typed bytes for Readline's next prompt. */
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <termios.h>
#include <unistd.h>

static struct termios original;
static int installed, reading;
static unsigned char queued[4096];
static size_t head, count;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

int neorepl_terminal_install(void) {
  if (installed || tcgetattr(0, &original) != 0) return -1;
  struct termios mode = original;
  mode.c_lflag &= ~(ISIG | ICANON | ECHO);
  mode.c_cc[VMIN] = 1;
  mode.c_cc[VTIME] = 0;
  if (tcsetattr(0, TCSANOW, &mode) != 0) return -1;
  installed = 1;
  head = count = reading = 0;
  return 0;
}
void neorepl_terminal_restore(void) {
  if (installed) tcsetattr(0, TCSANOW, &original);
  installed = 0;
}
void neorepl_input_mode(int active) {
  pthread_mutex_lock(&lock);
  reading = active;
  pthread_mutex_unlock(&lock);
}
int neorepl_key_poll(void) {
  int interrupted = 0;
  pthread_mutex_lock(&lock);
  struct pollfd input = {0, POLLIN, 0};
  if (!reading && count < sizeof queued && poll(&input, 1, 0) > 0 &&
      (input.revents & POLLIN)) {
    unsigned char c;
    if (read(0, &c, 1) == 1) {
      if (c == 3) interrupted = 1;
      else { queued[(head + count) % sizeof queued] = c; ++count; }
    }
  }
  pthread_mutex_unlock(&lock);
  return interrupted;
}
int neorepl_read_char(FILE *ignored) {
  (void)ignored;
  /* Readline's prep_terminal may re-enable ISIG after our baseline setup. */
  struct termios mode;
  if (tcgetattr(0, &mode) == 0) {
    mode.c_lflag &= ~ISIG;
    tcsetattr(0, TCSANOW, &mode);
  }
  pthread_mutex_lock(&lock);
  if (count) {
    unsigned char c = queued[head];
    head = (head + 1) % sizeof queued;
    --count;
    pthread_mutex_unlock(&lock);
    return c;
  }
  pthread_mutex_unlock(&lock);
  unsigned char c;
  ssize_t n;
  do { n = read(0, &c, 1); } while (n < 0 && errno == EINTR);
  return n == 1 ? c : EOF;
}
