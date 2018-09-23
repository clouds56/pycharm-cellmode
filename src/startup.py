import sys

def config_ipy(ipy):
    def _enable_formatter_type(t):
        if t not in ipy.display_formatter.active_types:
            ipy.display_formatter.active_types = ipy.display_formatter.active_types + [t]

    def enable_gui(gui=None, app=None):
        # print("enable_gui:", gui, app)
        from pydev_ipython.inputhook import enable_gui as real_enable_gui
        if gui == 'inline' and app is None:
            ipy.active_eventloop = gui
            return
        return real_enable_gui(gui, app)

    class PydevDisplayPublisher:
        def __init__(self):
            import zmq
            from jupyter_client.session import Session
            self.context = zmq.Context()
            self.socket = self.context.socket(zmq.PUB)
            self.port = self.socket.bind_to_random_port('tcp://127.0.0.1')
            self.session = Session(packer='json')

        def publish(self, data, metadata=None, source=None, transient=None, update=False):
            from ipykernel.jsonutil import json_clean, encode_images
            content = {
                'data': encode_images(data),
                'metadata': metadata,
                'transient': transient
            }
            msg_type = 'update_display_data' if update else 'display_data'
            msg = self.session.msg(
                msg_type, json_clean(content)
            )
            return self.session.send(self.socket, msg)
    pydev_publisher = PydevDisplayPublisher()
    sys.modules["pydev_publisher"] = pydev_publisher

    publish = ipy.display_pub.__class__.publish
    def publish_with_image(self, data, metadata=None, source=None, *, transient=None, update=False, **kwargs):
        result = publish.__get__(self, self.__class__)(data, metadata, source, transient=transient, update=update, **kwargs)
        if "image/png" in data:
            pydev_publisher.publish(data, metadata, source, transient=transient, update=update)
        return result

    import_hook_manager = sys.modules.get('_pydev_bundle.pydev_import_hook.import_hook', None)
    if import_hook_manager is not None and 'matplotlib.pyplot' in import_hook_manager._modules_to_patch:
        _activate_pyplot = import_hook_manager._modules_to_patch['matplotlib.pyplot']
        def activate_pyplot():
            if (hasattr(sys.modules['matplotlib.pyplot'], 'show') and
                hasattr(sys.modules['matplotlib.pyplot'], 'draw_if_interactive')):
                return _activate_pyplot()
            else:
                return False
        import_hook_manager._modules_to_patch['matplotlib.pyplot'] = activate_pyplot

    _enable_formatter_type("image/png")
    ipy.__setattr__("enable_gui", enable_gui)
    ipy.display_pub.__setattr__("publish", publish_with_image.__get__(ipy.display_pub, ipy.display_pub.__class__))

    return pydev_publisher.port

config_ipy(get_ipython())
