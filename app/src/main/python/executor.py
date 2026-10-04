import sys
import io
import traceback
import os

# Initialize to user storage immediately
try:
    os.chdir('/storage/emulated/0')
except:
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

def run_code(code, output_callback=None):
    global agent_globals
    if current_profile == 'Isolated':
        agent_globals = {'__name__': '__main__'}
        try: os.chdir('/storage/emulated/0')
        except: pass

    class StreamToCallback(io.RawIOBase):
        def write(self, b):
            # b might be bytes or string depending on the caller
            s = b.decode('utf-8') if isinstance(b, bytes) else str(b)
            if output_callback:
                try:
                    output_callback.onOutput(s)
                except: pass
            return len(s)

    # We use a custom writer that mimics a stream
    class FastStream:
        def write(self, s):
            if output_callback:
                try: output_callback.onOutput(s)
                except: pass
        def flush(self): pass

    output = FastStream()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = output, output
    
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
    except Exception:
        err = traceback.format_exc()
        print(err) # Force error into the callback stream
        return err
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr

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
        'docs': ['.pdf', '.docx', '.txt', '.epub', '.xlsx', '.md', '.rtf'],
        'zips': ['.zip', '.rar', '.7z', '.tar', '.gz'],
        'images': ['.jpg', '.jpeg', '.png', '.gif', '.webp', '.heic'],
        'videos': ['.mp4', '.mkv', '.mov', '.avi', '.webm'],
        'audio': ['.mp3', '.wav', '.flac', '.m4a', '.ogg'],
        'downloads': []
    }
    
    if category == 'downloads':
        download_path = '/storage/emulated/0/Download'
        if os.path.exists(download_path):
            return [os.path.join(download_path, f) for f in os.listdir(download_path) if os.path.isfile(os.path.join(download_path, f))]
        return []

    target_exts = extensions.get(category, [])
    found = []
    for root, dirs, files in os.walk(startpath):
        for file in files:
            if any(file.lower().endswith(ext) for ext in target_exts):
                found.append(os.path.join(root, file))
                if len(found) > 50: return found # Performance cap
    return found

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