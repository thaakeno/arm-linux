#!/usr/bin/env python3
import argparse
import os
import socket
import threading

import host_relay_direct as base

FRAME_ID = 0x7F000001
FRAME_SIZE = 16 * 1024 * 1024


def unix_listener(path):
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(path)
    s.listen(1)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--venus-unix', required=True)
    ap.add_argument('--uml-control', required=True)
    ap.add_argument('--listen', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=5002)
    ap.add_argument('--frame-path', required=True)
    args = ap.parse_args()

    ctrl_ls = unix_listener(args.uml_control)
    tcp_ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp_ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp_ls.bind((args.listen, args.port))
    tcp_ls.listen(4)

    print(f'[host-frame] waiting for patched UML on {args.uml_control}', flush=True)
    ctrl, _ = ctrl_ls.accept()
    control = base.Control(ctrl)
    print('[host-frame] patched UML control connected', flush=True)

    frame_fd = os.open(args.frame_path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o600)
    os.ftruncate(frame_fd, FRAME_SIZE)
    control.register(FRAME_ID, frame_fd, FRAME_SIZE)
    print(f'[host-frame] registered shared frame id={FRAME_ID} size={FRAME_SIZE} path={args.frame_path}', flush=True)

    next_id = 1
    id_lock = threading.Lock()
    def alloc_id():
        nonlocal next_id
        with id_lock:
            while next_id == FRAME_ID:
                next_id += 1
            obj_id = next_id
            next_id += 1
            if next_id > 0xffffffff:
                next_id = 1
            return obj_id

    try:
        while True:
            print(f'[host-frame] waiting for Debian relay on {args.listen}:{args.port}', flush=True)
            tcp, addr = tcp_ls.accept()
            print(f'[host-frame] Debian relay connected from {addr}', flush=True)
            try:
                base.Relay(args.venus_unix, tcp, control, alloc_id).run()
            except Exception as e:
                print(f'[host-frame] session ended with error: {e}', flush=True)
            finally:
                try:
                    tcp.close()
                except OSError:
                    pass
            print('[host-frame] session finished; ready for next Debian client', flush=True)
    except (KeyboardInterrupt, EOFError, BrokenPipeError, ConnectionResetError):
        print('[host-frame] stopping', flush=True)
    finally:
        try:
            control.unregister(FRAME_ID)
        except Exception as e:
            print(f'[host-frame] frame unregister warning: {e}', flush=True)
        os.close(frame_fd)
        ctrl.close()
        tcp_ls.close()
        ctrl_ls.close()
        try:
            os.unlink(args.uml_control)
        except FileNotFoundError:
            pass

if __name__ == '__main__':
    main()
