import sys
import io
import traceback

try:
    import pip_manager
    pip_manager.ensure_site_dir()
except Exception:
    pass

# Persistent dictionary to store variables across terminal inputs
terminal_globals = {}

def run_repl_line(code):
    import os
    output = io.StringIO()
    sys.stdout = output
    sys.stderr = output
    
    # Handle Shell Magic (!command)
    if code.strip().startswith('!'):
        cmd = code.strip()[1:].strip()
        if cmd == 'pip' or cmd.startswith('pip '):
            import pip_manager
            return pip_manager.handle_pip_command(cmd)
        else:
            return os.popen(cmd).read()

    try:
        # Try evaluating as an expression first (to show return values like a real REPL)
        try:
            result = eval(code, terminal_globals)
            if result is not None:
                print(repr(result))
        except SyntaxError:
            # If not an expression, execute as a statement
            exec(code, terminal_globals)
            
        res_str = output.getvalue()
    except Exception:
        res_str = traceback.format_exc()
    finally:
        sys.stdout = sys.__stdout__
        sys.stderr = sys.__stderr__
        
    return res_str.strip()