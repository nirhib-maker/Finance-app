"""Generate golden test vectors for the Kotlin Switcher protocol port, using aioswitcher itself.

    pip install aioswitcher==6.6.1
    TZ=Asia/Jerusalem python tools/switcher_vectors.py > core/src/test/resources/switcher_vectors.json
"""
import json
import time
from binascii import hexlify, unhexlify

from aioswitcher.api import packets
from aioswitcher.api.messages import SwitcherGetSchedulesResponse, SwitcherStateResponse
from aioswitcher.bridge import DatagramParser
from aioswitcher.device.tools import (
    minutes_to_hexadecimal_seconds,
    set_message_length,
    sign_packet_with_crc_key,
)
from aioswitcher.schedule.tools import time_to_hexadecimal_timestamp

TS = "a1b2c3d4"
SESSION = "0badf00d"
DEVICE_ID = "ab1c2d"
DEVICE_KEY = "18"


def final(p):
    return sign_packet_with_crc_key(set_message_length(p))


start_hex = time_to_hexadecimal_timestamp("17:20")
end_hex = time_to_hexadecimal_timestamp("19:50")
v = {
    "date": time.strftime("%Y-%m-%d"),
    "startHex": start_hex,
    "endHex": end_hex,
    "minutes90": minutes_to_hexadecimal_seconds(90),
    "login": final(packets.LOGIN_PACKET_TYPE1.format(TS, DEVICE_KEY)),
    "getState": final(packets.GET_STATE_PACKET_TYPE1.format(SESSION, TS, DEVICE_ID)),
    "on90": final(packets.SEND_CONTROL_PACKET.format(SESSION, TS, DEVICE_ID, "1", minutes_to_hexadecimal_seconds(90))),
    "off": final(packets.SEND_CONTROL_PACKET.format(SESSION, TS, DEVICE_ID, "0", packets.NO_TIMER_REQUESTED)),
    "getSchedules": final(packets.GET_SCHEDULES_PACKET.format(SESSION, TS, DEVICE_ID)),
    "delete3": final(packets.DELETE_SCHEDULE_PACKET.format(SESSION, TS, DEVICE_ID, "3")),
    "create": final(packets.CREATE_SCHEDULE_PACKET.format(
        SESSION, TS, DEVICE_ID,
        packets.SCHEDULE_CREATE_DATA_FORMAT.format(packets.NON_RECURRING_SCHEDULE, start_hex, end_hex))),
}

# A state response: zeros with the fields aioswitcher reads (hex offsets 150..202).
h = list("00" * 110)
def put(hex_offset, value):
    h[hex_offset:hex_offset + len(value)] = list(value)
put(150, "01")                  # state ON
put(154, "6a09")                # 0x096a = 2410 W (little endian)
put(178, "08070000")            # time left 0x708 = 1800 s
put(194, "301b0000")            # auto shutdown 0x1b30 = 6960 s
state_bytes = unhexlify("".join(h))
st = SwitcherStateResponse(state_bytes)
v["stateResponse"] = "".join(h)
v["state"] = {"on": st.state.value == "01", "watts": st.power_consumption,
              "timeLeft": st.time_left, "autoShutdown": st.auto_shutdown}

# A get-schedules response: 45 header bytes, two schedules (one-time, recurring), 4 trailing bytes.
t1 = int(time.mktime(time.strptime(v["date"] + " 17:20", "%Y-%m-%d %H:%M")))
t2 = int(time.mktime(time.strptime(v["date"] + " 19:50", "%Y-%m-%d %H:%M")))
le = lambda x: hexlify(x.to_bytes(4, "little")).decode()
sched = "".join([
    "00" * 45,
    "03" + "01" + "00" + "01" + le(t1) + le(t2) + "00" * 4,    # id 3, enabled, one-time
    "05" + "01" + "fe" + "01" + le(t1) + le(t2) + "00" * 4,    # id 5, recurring every day
    "00" * 4,
])
resp = SwitcherGetSchedulesResponse(unhexlify(sched))
v["schedulesResponse"] = sched
v["schedules"] = sorted(
    [{"id": s.schedule_id, "recurring": s.recurring, "start": s.start_time, "end": s.end_time} for s in resp.schedules],
    key=lambda s: s["id"])

# A discovery broadcast of a Switcher V4 (165 bytes).
d = bytearray(165)
d[0:2] = b"\xfe\xf0"
d[18:21] = unhexlify(DEVICE_ID)
d[42:42 + len("Boiler")] = b"Boiler"
d[40:41] = unhexlify(DEVICE_KEY)
d[74:76] = unhexlify("0317")
d[133:134] = b"\x01"            # state ON (hex offset 266)
d[135:137] = unhexlify("6a09")  # power (hex offset 270)
p = DatagramParser(bytes(d))
v["discovery"] = hexlify(bytes(d)).decode()
v["discovered"] = {"originator": p.is_switcher_originator(), "type": p.get_device_type().name,
                   "id": p.get_device_id(), "key": p.get_device_key(), "name": p.get_name(),
                   "on": p.get_device_state().value == "01", "watts": p.get_power_consumption()}

print(json.dumps(v, indent=2))
