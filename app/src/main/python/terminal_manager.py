import sys
import io
import traceback

# Persistent dictionary to store variables across terminal inputs
terminal_globals = {}

def run_repl_line(code):
    import os
    output = io.StringIO()
    sys.stdout = output
    sys.stderr = output
    
    # Handle Shell Magic (!command)
    if code.strip().startswith('!'):
        cmd = code.strip()[1:]
        if cmd.startswith('pip '):
            import pip_manager
            pkg = cmd.replace('pip install', '').strip()
            return pip_manager.install_package(pkg)
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