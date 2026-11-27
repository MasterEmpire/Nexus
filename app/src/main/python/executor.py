import sys
import io
import traceback
import os
import builtins
import time
import threading
import ctypes

_stop_requested = False
_active_thread_id = None
_orig_sleep = time.sleep


def _interruptible_sleep(seconds):
    end = time.monotonic() + max(0.0, float(seconds))
    while True:
        if _stop_requested:
            raise KeyboardInterrupt("Execution stopped by user")
        remaining = end - time.monotonic()
        if remaining <= 0:
            break
        _orig_sleep(min(0.1, remaining))


time.sleep = _interruptible_sleep


def request_stop():
    global _stop_requested
    _stop_requested = True
    tid = _active_thread_id
    if tid is not None:
        try:
            res = ctypes.pythonapi.PyThreadState_SetAsyncExc(
                ctypes.c_ulong(tid), ctypes.py_object(KeyboardInterrupt)
            )
            if res > 1:
                ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(tid), None)
        except Exception:
            pass
    return "Stop requested"

# Initialize to user storage immediately
try:
    os.chdir('/storage/emulated/0')
except:
    pass
try:
    import pip_manager
    pip_manager.ensure_site_dir()
except Exception:
    pass

import gc

# Global state for the AI Agent
agent_globals = {'__name__': '__main__'}
current_profile = "Standard"

def set_profile(profile):
    global current_profile
    current_profile = profile
    if profile == "Optimized":
        try:
            gc.set_threshold(1000, 15, 15)
            sys.setswitchinterval(0.015)
        except: pass
    else:
        try:
            gc.set_threshold(700, 10, 10)
            sys.setswitchinterval(0.005)
        except: pass
    return f"Profile set to {profile}"

def reset_state():
    global agent_globals
    agent_globals = {'__name__': '__main__'}
    import os
    os.chdir('/storage/emulated/0')
    return "Interpreter state cleared."

import ast

def run_code(code, output_callback=None, input_callback=None):
    global agent_globals, _stop_requested, _active_thread_id
    _stop_requested = False
    _active_thread_id = threading.get_ident()

    if current_profile == 'Isolated':
        agent_globals = {'__name__': '__main__'}
        try: os.chdir('/storage/emulated/0')
        except: pass

    # Custom writer that mimics a stream
    class FastStream:
        def write(self, s):
            if _stop_requested:
                raise KeyboardInterrupt("Execution stopped by user")
            if output_callback:
                try: output_callback.onOutput(s)
                except: pass
        def flush(self): pass

    # Custom reader that bridges stdin to Kotlin
    class FastStdin(io.TextIOBase):
        def readline(self):
            if _stop_requested:
                raise KeyboardInterrupt("Execution stopped by user")
            if input_callback:
                try:
                    res = input_callback.onInputRequested("")
                    if _stop_requested:
                        raise KeyboardInterrupt("Execution stopped by user")
                    return str(res) if str(res).endswith('\n') else str(res) + '\n'
                except KeyboardInterrupt:
                    raise
                except Exception:
                    return '\n'
            return '\n'

    output = FastStream()
    fast_stdin = FastStdin()
    old_stdout, old_stderr, old_stdin = sys.stdout, sys.stderr, sys.stdin
    old_input = builtins.input

    def custom_input(prompt=""):
        if _stop_requested:
            raise KeyboardInterrupt("Execution stopped by user")
        if prompt and output_callback:
            try: output_callback.onOutput(str(prompt))
            except: pass
        if input_callback:
            try:
                res = input_callback.onInputRequested(str(prompt))
                if _stop_requested:
                    raise KeyboardInterrupt("Execution stopped by user")
                return str(res).rstrip('\r\n')
            except KeyboardInterrupt:
                raise
            except Exception:
                return ""
        return ""

    sys.stdout, sys.stderr = output, output
    sys.stdin = fast_stdin
    builtins.input = custom_input
    
    try:
        # Clean up the code and parse it into an AST
        source = code.strip()
        if not source: return ""
        
        tree = ast.parse(source)
        
        # If the last node is an expression, we want to eval it to see the output
        # (e.g., just typing '1+1' or 'my_var')
        last_node = tree.body[-1] if tree.body else None
        
        if isinstance(last_node, ast.Expr):
            # Execute everything EXCEPT the last expression
            exec_tree = ast.Module(body=tree.body[:-1], type_ignores=[])
            exec(compile(exec_tree, filename="<ast>", mode="exec"), agent_globals)
            
            # Now eval the last expression and print its result
            result = eval(compile(ast.Expression(body=last_node.value), filename="<ast>", mode="eval"), agent_globals)
            if result is not None:
                print(repr(result))
        else:
            # Just execute the whole thing as a block
            exec(compile(tree, filename="<ast>", mode="exec"), agent_globals)
            
        return ""
    except KeyboardInterrupt:
        msg = "\n[STOPPED BY USER]\n"
        if output_callback:
            try: output_callback.onOutput(msg)
            except: pass
        return msg
    except Exception:
        err = traceback.format_exc()
        print(err) # Force error into the callback stream
        return err
    finally:
        _active_thread_id = None
        _stop_requested = False
        sys.stdout, sys.stderr = old_stdout, old_stderr
        sys.stdin = old_stdin
        builtins.input = old_input

def get_structure(startpath='/storage/emulated/0', max_depth=2):
    import os
    output = [f"Structure of {startpath}:"]
    
    def walk(path, depth):
        if depth > max_depth:
            return
        try:
            files = sorted(os.listdir(path))
            for i, f in enumerate(files):
                if f.startswith('.'): continue
                full_path = os.path.join(path, f)
                is_dir = os.path.isdir(full_path)
                prefix = "  " * depth + "├── "
                output.append(f"{prefix}{f}{'/' if is_dir else ''}")
                if is_dir:
                    walk(full_path, depth + 1)
        except PermissionError:
            output.append("  " * depth + "└── [Permission Denied]")
        except Exception as e:
            output.append("  " * depth + f"└── [Error: {str(e)}]")

    walk(startpath, 0)
    return "\n".join(output)

def find_files_by_type(category, startpath='/storage/emulated/0'):
    import os
    extensions = {
        'docs': ['.pdf', '.doc', '.docx', '.txt', '.epub', '.xls', '.xlsx', '.ppt', '.pptx', '.odt', '.csv', '.md', '.rtf'],
        'zips': ['.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.xz'],
        'images': ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.heic'],
        'videos': ['.mp4', '.mkv', '.mov', '.avi', '.webm'],
        'audio': ['.mp3', '.wav', '.flac', '.m4a', '.ogg'],
        'downloads': []
    }

    if category == 'downloads':
        download_path = '/storage/emulated/0/Download'
        if os.path.exists(download_path):
            return [os.path.join(download_path, f) for f in os.listdir(download_path) if not f.startswith('.') and os.path.isfile(os.path.join(download_path, f))]
        return []

    target_exts = tuple(extensions.get(category, []))
    skip_android_sub = {'data', 'obb', 'media'}
    found = []
    for root, dirs, files in os.walk(startpath):
        dirs[:] = [
            d for d in dirs
            if not d.startswith('.') and not (root.endswith('/Android') and d in skip_android_sub)
        ]
        for file in files:
            if file.startswith('.'):
                continue
            if file.lower().endswith(target_exts):
                full_path = os.path.join(root, file)
                try:
                    mtime = os.path.getmtime(full_path)
                except Exception:
                    mtime = 0
                found.append((mtime, full_path))
                if len(found) >= 250:
                    break
        if len(found) >= 250:
            break

    found.sort(key=lambda item: item[0], reverse=True)
    return [p for _, p in found[:100]]

def search_files(query, startpath='/storage/emulated/0'):
    import os
    found = []
    query = query.lower()
    for root, dirs, files in os.walk(startpath):
        for file in files:
            if query in file.lower():
                found.append(os.path.join(root, file))
                if len(found) > 100: return found
    return found