use std::collections::VecDeque;
use std::env;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::UnixDatagram;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

use vhost::vhost_user::message::{VhostUserProtocolFeatures, VhostUserVirtioFeatures};
use vhost_user_backend::{VhostUserBackendMut, VhostUserDaemon, VringRwLock, VringT};
use virtio_bindings::bindings::{
    virtio_config::VIRTIO_F_VERSION_1,
    virtio_ring::{VIRTIO_RING_F_EVENT_IDX, VIRTIO_RING_F_INDIRECT_DESC},
};
use virtio_queue::QueueT;
use vm_memory::{ByteValued, Bytes, GuestAddressSpace, GuestMemoryAtomic, GuestMemoryMmap};
use vmm_sys_util::{
    epoll::EventSet,
    event::{new_event_consumer_and_notifier, EventConsumer, EventFlag, EventNotifier},
};

const EVENT_QUEUE: u16 = 0;
const STATUS_QUEUE: u16 = 1;
const CONTROL_EVENT_ID: u64 = 3;
const NUM_QUEUES: usize = 2;
const QUEUE_SIZE: usize = 1024;
const CFG_SIZE: usize = 128;

const CFG_ID_NAME: u8 = 0x01;
const CFG_ID_SERIAL: u8 = 0x02;
const CFG_ID_DEVIDS: u8 = 0x03;
const CFG_PROP_BITS: u8 = 0x10;
const CFG_EV_BITS: u8 = 0x11;
const CFG_ABS_INFO: u8 = 0x12;

const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const EV_ABS: u16 = 0x03;
const EV_REP: u16 = 0x14;
const SYN_REPORT: u16 = 0;

const REL_X: usize = 0;
const REL_Y: usize = 1;
const REL_HWHEEL: usize = 6;
const REL_WHEEL: usize = 8;
const ABS_X: usize = 0;
const ABS_Y: usize = 1;
const BTN_LEFT: usize = 0x110;
const BTN_RIGHT: usize = 0x111;
const BTN_MIDDLE: usize = 0x112;
const BTN_TOUCH: usize = 0x14a;
const INPUT_PROP_POINTER: usize = 0;
const INPUT_PROP_DIRECT: usize = 1;
const BUS_VIRTUAL: u16 = 0x06;

#[repr(C, packed)]
#[derive(Copy, Clone, Default)]
struct VuInputEvent {
    ev_type: u16,
    code: u16,
    value: u32,
}

unsafe impl ByteValued for VuInputEvent {}

#[repr(C, packed)]
#[derive(Copy, Clone)]
struct VuInputConfig {
    select: u8,
    subsel: u8,
    size: u8,
    reserved: [u8; 5],
    val: [u8; CFG_SIZE],
}

impl Default for VuInputConfig {
    fn default() -> Self {
        Self { select: 0, subsel: 0, size: 0, reserved: [0; 5], val: [0; CFG_SIZE] }
    }
}

unsafe impl ByteValued for VuInputConfig {}

#[derive(Copy, Clone, Debug)]
enum DeviceKind {
    Touch,
    Pointer,
    Keyboard,
}

impl DeviceKind {
    fn parse(s: &str) -> io::Result<Self> {
        match s {
            "touch" => Ok(Self::Touch),
            "pointer" | "trackpad" => Ok(Self::Pointer),
            "keyboard" => Ok(Self::Keyboard),
            _ => Err(io::Error::new(io::ErrorKind::InvalidInput, format!("unknown device kind: {s}"))),
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Touch => "Vessel Touchscreen",
            Self::Pointer => "Vessel Trackpad",
            Self::Keyboard => "Vessel Keyboard",
        }
    }

    fn product(self) -> u16 {
        match self {
            Self::Touch => 0x0031,
            Self::Pointer => 0x0032,
            Self::Keyboard => 0x0033,
        }
    }
}

struct DeviceSpec {
    kind: DeviceKind,
}

impl DeviceSpec {
    fn new(kind: DeviceKind) -> Self { Self { kind } }

    fn bitmap(bits: &[usize]) -> (u8, [u8; CFG_SIZE]) {
        let mut out = [0u8; CFG_SIZE];
        let mut size = 0usize;
        for &bit in bits {
            let byte = bit / 8;
            if byte >= out.len() { continue; }
            out[byte] |= 1u8 << (bit % 8);
            size = size.max(byte + 1);
        }
        (size as u8, out)
    }

    fn string_config(select: u8, text: &str) -> VuInputConfig {
        let mut cfg = VuInputConfig { select, ..VuInputConfig::default() };
        let bytes = text.as_bytes();
        let n = bytes.len().min(CFG_SIZE - 1);
        cfg.val[..n].copy_from_slice(&bytes[..n]);
        cfg.size = (n + 1) as u8;
        cfg
    }

    fn ids_config(&self) -> VuInputConfig {
        let mut cfg = VuInputConfig { select: CFG_ID_DEVIDS, size: 8, ..VuInputConfig::default() };
        let values = [BUS_VIRTUAL, 0x5653u16, self.kind.product(), 0x0039u16];
        for (i, v) in values.into_iter().enumerate() {
            cfg.val[i * 2..i * 2 + 2].copy_from_slice(&v.to_le_bytes());
        }
        cfg
    }

    fn prop_config(&self) -> VuInputConfig {
        let bits: &[usize] = match self.kind {
            DeviceKind::Touch => &[INPUT_PROP_DIRECT],
            DeviceKind::Pointer => &[INPUT_PROP_POINTER],
            DeviceKind::Keyboard => &[],
        };
        let (size, val) = Self::bitmap(bits);
        VuInputConfig { select: CFG_PROP_BITS, size, val, ..VuInputConfig::default() }
    }

    fn event_bits_config(&self, subsel: u8) -> VuInputConfig {
        let bits: Vec<usize> = match (self.kind, u16::from(subsel)) {
            (DeviceKind::Touch, EV_KEY) => vec![BTN_TOUCH],
            (DeviceKind::Touch, EV_ABS) => vec![ABS_X, ABS_Y],
            (DeviceKind::Pointer, EV_KEY) => vec![BTN_LEFT, BTN_RIGHT, BTN_MIDDLE],
            (DeviceKind::Pointer, EV_REL) => vec![REL_X, REL_Y, REL_HWHEEL, REL_WHEEL],
            (DeviceKind::Keyboard, EV_KEY) => (1usize..=255).collect(),
            (DeviceKind::Keyboard, EV_REP) => vec![0, 1],
            _ => Vec::new(),
        };
        let (size, val) = Self::bitmap(&bits);
        VuInputConfig { select: CFG_EV_BITS, subsel, size, val, ..VuInputConfig::default() }
    }

    fn abs_config(&self, subsel: u8) -> VuInputConfig {
        if !matches!(self.kind, DeviceKind::Touch) || !matches!(usize::from(subsel), ABS_X | ABS_Y) {
            return VuInputConfig { select: CFG_ABS_INFO, subsel, ..VuInputConfig::default() };
        }
        let mut cfg = VuInputConfig { select: CFG_ABS_INFO, subsel, size: 20, ..VuInputConfig::default() };
        let fields = [0u32, 32767u32, 0u32, 0u32, 0u32];
        for (i, v) in fields.into_iter().enumerate() {
            cfg.val[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
        }
        cfg
    }

    fn config(&self, select: u8, subsel: u8) -> VuInputConfig {
        match select {
            CFG_ID_NAME => Self::string_config(CFG_ID_NAME, self.kind.name()),
            CFG_ID_SERIAL => Self::string_config(CFG_ID_SERIAL, "Vessel-v39"),
            CFG_ID_DEVIDS => self.ids_config(),
            CFG_PROP_BITS => self.prop_config(),
            CFG_EV_BITS => self.event_bits_config(subsel),
            CFG_ABS_INFO => self.abs_config(subsel),
            _ => VuInputConfig { select, subsel, ..VuInputConfig::default() },
        }
    }
}

struct VesselInputBackend {
    event_idx: bool,
    control: UnixDatagram,
    spec: DeviceSpec,
    exit_consumer: EventConsumer,
    exit_notifier: EventNotifier,
    select: u8,
    subsel: u8,
    mem: Option<GuestMemoryAtomic<GuestMemoryMmap>>,
    pending: VecDeque<VuInputEvent>,
}

impl VesselInputBackend {
    fn new(control: UnixDatagram, spec: DeviceSpec) -> io::Result<Self> {
        let (exit_consumer, exit_notifier) = new_event_consumer_and_notifier(EventFlag::NONBLOCK)?;
        control.set_nonblocking(true)?;
        Ok(Self {
            event_idx: false,
            control,
            spec,
            exit_consumer,
            exit_notifier,
            select: 0,
            subsel: 0,
            mem: None,
            pending: VecDeque::with_capacity(256),
        })
    }

    fn control_fd(&self) -> RawFd { self.control.as_raw_fd() }

    fn read_control(&mut self) -> io::Result<()> {
        let mut buf = [0u8; 4096];
        loop {
            match self.control.recv(&mut buf) {
                Ok(n) => {
                    if n % 8 != 0 {
                        log::warn!("discarding malformed Vessel input packet of {n} bytes");
                        continue;
                    }
                    for chunk in buf[..n].chunks_exact(8) {
                        let ev_type = u16::from_le_bytes([chunk[0], chunk[1]]);
                        let code = u16::from_le_bytes([chunk[2], chunk[3]]);
                        let value = u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]);
                        self.pending.push_back(VuInputEvent { ev_type, code, value });
                    }
                    if self.pending.len() > 8192 {
                        log::warn!("input queue overflow; retaining newest complete frame");
                        while self.pending.len() > 4096 { self.pending.pop_front(); }
                        while self.pending.front().is_some_and(|e| !(e.ev_type == EV_SYN && e.code == SYN_REPORT)) {
                            self.pending.pop_front();
                        }
                        if self.pending.front().is_some() { self.pending.pop_front(); }
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(e),
            }
        }
        Ok(())
    }

    fn process_event_queue(&mut self, vring: &VringRwLock) -> io::Result<()> {
        let Some(mem) = self.mem.as_ref().map(GuestAddressSpace::memory) else { return Ok(()); };
        let mut wrote = false;
        loop {
            let Some(event) = self.pending.front().copied() else { break; };
            let desc = vring.get_mut().get_queue_mut().pop_descriptor_chain(mem.clone());
            let Some(desc_chain) = desc else { break; };
            let descriptors: Vec<_> = desc_chain.clone().collect();
            if descriptors.len() != 1 {
                return Err(io::Error::other(format!("virtio-input expected one descriptor, got {}", descriptors.len())));
            }
            let descriptor = descriptors[0];
            desc_chain.memory().write_obj(event, descriptor.addr())
                .map_err(|e| io::Error::other(format!("virtio-input write failed: {e}")))?;
            vring.add_used(desc_chain.head_index(), 8)
                .map_err(|e| io::Error::other(format!("virtio-input add_used failed: {e}")))?;
            self.pending.pop_front();
            wrote = true;
        }
        if wrote {
            vring.signal_used_queue()
                .map_err(|e| io::Error::other(format!("virtio-input signal failed: {e}")))?;
        }
        Ok(())
    }

    fn process_status_queue(&mut self, vring: &VringRwLock) -> io::Result<()> {
        let Some(mem) = self.mem.as_ref().map(GuestAddressSpace::memory) else { return Ok(()); };
        let mut consumed = false;
        loop {
            let desc = vring.get_mut().get_queue_mut().pop_descriptor_chain(mem.clone());
            let Some(desc_chain) = desc else { break; };
            vring.add_used(desc_chain.head_index(), 0)
                .map_err(|e| io::Error::other(format!("virtio-input status add_used failed: {e}")))?;
            consumed = true;
        }
        if consumed {
            vring.signal_used_queue()
                .map_err(|e| io::Error::other(format!("virtio-input status signal failed: {e}")))?;
        }
        Ok(())
    }
}

impl VhostUserBackendMut for VesselInputBackend {
    type Bitmap = ();
    type Vring = VringRwLock;

    fn num_queues(&self) -> usize { NUM_QUEUES }
    fn max_queue_size(&self) -> usize { QUEUE_SIZE }

    fn features(&self) -> u64 {
        (1 << VIRTIO_F_VERSION_1)
            | (1 << VIRTIO_RING_F_INDIRECT_DESC)
            | (1 << VIRTIO_RING_F_EVENT_IDX)
            | VhostUserVirtioFeatures::PROTOCOL_FEATURES.bits()
    }

    fn protocol_features(&self) -> VhostUserProtocolFeatures {
        VhostUserProtocolFeatures::MQ | VhostUserProtocolFeatures::CONFIG
    }

    fn get_config(&self, offset: u32, size: u32) -> Vec<u8> {
        let cfg = self.spec.config(self.select, self.subsel);
        let bytes = cfg.as_slice();
        let mut out: Vec<u8> = bytes.iter().skip(offset as usize).take(size as usize).copied().collect();
        out.resize(size as usize, 0);
        out
    }

    fn set_config(&mut self, offset: u32, buf: &[u8]) -> io::Result<()> {
        for (i, &byte) in buf.iter().enumerate() {
            match offset as usize + i {
                0 => self.select = byte,
                1 => self.subsel = byte,
                _ => {}
            }
        }
        Ok(())
    }

    fn set_event_idx(&mut self, enabled: bool) { self.event_idx = enabled; }

    fn update_memory(&mut self, mem: GuestMemoryAtomic<GuestMemoryMmap>) -> io::Result<()> {
        self.mem = Some(mem);
        Ok(())
    }

    fn handle_event(
        &mut self,
        device_event: u16,
        evset: EventSet,
        vrings: &[VringRwLock],
        _thread_id: usize,
    ) -> io::Result<()> {
        if !evset.contains(EventSet::IN) {
            return Ok(());
        }
        match device_event {
            EVENT_QUEUE => {
                if self.event_idx {
                    vrings[0].disable_notification()
                        .map_err(|e| io::Error::other(format!("virtio-input disable notification failed: {e}")))?;
                }
                let r = self.process_event_queue(&vrings[0]);
                if self.event_idx {
                    vrings[0].enable_notification()
                        .map_err(|e| io::Error::other(format!("virtio-input enable notification failed: {e}")))?;
                }
                r
            }
            STATUS_QUEUE => self.process_status_queue(&vrings[1]),
            x if u64::from(x) == CONTROL_EVENT_ID => {
                self.read_control()?;
                if self.event_idx {
                    vrings[0].disable_notification()
                        .map_err(|e| io::Error::other(format!("virtio-input disable notification failed: {e}")))?;
                }
                let r = self.process_event_queue(&vrings[0]);
                if self.event_idx {
                    vrings[0].enable_notification()
                        .map_err(|e| io::Error::other(format!("virtio-input enable notification failed: {e}")))?;
                }
                r
            }
            _ => Ok(()),
        }
    }

    fn exit_event(&self, _thread_index: usize) -> Option<(EventConsumer, EventNotifier)> {
        Some((self.exit_consumer.try_clone().ok()?, self.exit_notifier.try_clone().ok()?))
    }
}

fn arg(name: &str) -> io::Result<String> {
    let mut args = env::args().skip(1);
    while let Some(v) = args.next() {
        if v == name {
            return args.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("missing value for {name}")));
        }
        if let Some(rest) = v.strip_prefix(&(name.to_owned() + "=")) {
            return Ok(rest.to_owned());
        }
    }
    Err(io::Error::new(io::ErrorKind::InvalidInput, format!("required argument {name}")))
}

fn remove_stale(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    let socket_path = PathBuf::from(arg("--socket-path")?);
    let control_path = PathBuf::from(arg("--control-path")?);
    let kind = DeviceKind::parse(&arg("--device")?)?;

    if let Some(parent) = socket_path.parent() { fs::create_dir_all(parent)?; }
    if let Some(parent) = control_path.parent() { fs::create_dir_all(parent)?; }
    remove_stale(&socket_path)?;
    remove_stale(&control_path)?;

    let control = UnixDatagram::bind(&control_path)?;
    let backend = VesselInputBackend::new(control, DeviceSpec::new(kind))?;
    let control_fd = backend.control_fd();
    let backend = Arc::new(RwLock::new(backend));
    let mut daemon = VhostUserDaemon::new(
        format!("vessel-virtio-input-{}", kind.name()),
        Arc::clone(&backend),
        GuestMemoryAtomic::new(GuestMemoryMmap::new()),
    )
    .map_err(|e| io::Error::other(format!("create vhost-user daemon failed: {e:?}")))?;
    daemon.get_epoll_handlers()[0].register_listener(control_fd, EventSet::IN, CONTROL_EVENT_ID)?;

    log::info!("Vessel {:?} backend ready: vhost={} control={}", kind, socket_path.display(), control_path.display());
    let result = daemon.serve(&socket_path);
    let _ = remove_stale(&control_path);
    let _ = remove_stale(&socket_path);
    result.map_err(|e| io::Error::other(format!("vhost-user serve failed: {e:?}")))?;
    Ok(())
}
