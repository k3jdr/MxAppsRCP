#!/usr/bin/env python3
"""MxDataHUB's Data Refresh on a schedule, on the PC that hosts the security master (Windows Task Scheduler).

It is the refresh MxDataHUB's Data Refresh screen runs: the SRM loader service (MxAppsIOS Services/srm-loader,
127.0.0.1:8770) refreshes each data category with srm.py's loaders, in dependency order, and the run stops at the
first category that fails. A scheduled run refreshes every daily category and the weekly and monthly ones that are
no longer fresh, not the optional Portfolio snapshots; it writes what the loaders print to its log and ends with the
service's data-quality checks. MxDataHUB's Data Refresh ▸ Activity shows the run while it happens, Load History after.

    srm_refresh.py run                          refresh now, as the scheduled task does
    srm_refresh.py run --dry-run                only list the srm.py command each loader would run
    srm_refresh.py run --all                    every category, fresh or not (Refresh All)
    srm_refresh.py run --categories prices,corporate-actions
    srm_refresh.py install [--time 18:30] [--days mon-fri] [run options]    the task "MxDataHUB SRM Refresh"
    srm_refresh.py uninstall
    srm_refresh.py status                       the task's last and next run, the last refresh, the saved secrets

Run options: --skip a,b  --include-optional  --keep-going (go on after a failed category)  --verbosity 1|2
--log-dir DIR (default %LOCALAPPDATA%\\MxAppsRCP\\MxDataHUB\\refresh)  --ios-repo DIR (MXAPPSIOS_HOME, else
D:\\MyCodeGIT\\MxAppsIOS)  --wait-minutes N (for a load that is already running; 120)

The service is started (Services/mxservices.py start srm-loader) when it isn't running, and left running for
MxDataHUB. srm.py finds the database password and API keys in the Credential Manager (see `status`).
Exit code: 0 refreshed (some loaders may be partial), 1 a category failed or the service or database could not be
reached, 2 wrong arguments.
"""

import argparse
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from xml.sax.saxutils import escape

TASK = "MxDataHUB SRM Refresh"
BASE = "http://127.0.0.1:8770"
WINDOWS = sys.platform == "win32"
NO_WINDOW = subprocess.CREATE_NO_WINDOW if WINDOWS else 0
KEEP_LOGS = 90
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
DEFAULT_LOG_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "MxAppsRCP", "MxDataHUB",
                               "refresh")
DEFAULT_IOS_REPO = os.environ.get("MXAPPSIOS_HOME") or r"D:\MyCodeGIT\MxAppsIOS"
# Never through a proxy: the service listens on 127.0.0.1 only.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def secrets():
    """The Credential Manager entries srm.py reads ("service:account"), and what needs each."""
    db = "%s@%s/%s" % (os.environ.get("SRM_PGUSER") or "postgres", os.environ.get("SRM_PGHOST") or "192.168.0.223",
                       os.environ.get("SRM_PGDATABASE") or "srm")
    return [("SRM-postgres:" + db, "the database password: every loader"),
            ("SRM-api:fmp", "FMP: prices, profiles, corporate actions, fundamentals, earnings symbols"),
            ("SRM-api:openfigi", "OpenFIGI: identifiers (slower without a key)"),
            ("SRM-api:massive-s3-access", "Massive flat files: every U.S. listing's daily bar"),
            ("SRM-api:massive-s3-secret", "Massive flat files (the secret key)")]


def call(method, url, timeout, headers=None):
    """(HTTP status, JSON body). OSError when the service doesn't answer."""
    request = urllib.request.Request(url, data=b"" if method == "POST" else None, method=method, headers=headers or {})
    try:
        with OPENER.open(request, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"error": body.strip()}


def healthy():
    try:
        return call("GET", BASE + "/health", 5)[0] == 200
    except OSError:
        return False


def python_exe():
    """python.exe beside pythonw.exe (the task runs pythonw, which has no console for the service to share)."""
    exe = sys.executable
    if os.path.basename(exe).lower() == "pythonw.exe":
        console = os.path.join(os.path.dirname(exe), "python.exe")
        if os.path.exists(console):
            return console
    return exe


class RunLog:
    """The run's log file, refresh-<date>-<time>.log in the log folder (the last 90 kept), and the console if any."""

    def __init__(self, folder):
        os.makedirs(folder, exist_ok=True)
        self.path = os.path.join(folder, dt.datetime.now().strftime("refresh-%Y-%m-%d-%H%M%S.log"))
        self.file = open(self.path, "w", encoding="utf-8")
        self.lock = threading.Lock()
        for old in sorted(glob.glob(os.path.join(folder, "refresh-*.log")))[:-KEEP_LOGS]:
            try:
                os.remove(old)
            except OSError:
                pass

    def __call__(self, text, clock=None):
        line = "%s  %s" % (clock or dt.datetime.now().strftime("%H:%M:%S"), text)
        with self.lock:
            self.file.write(line + "\n")
            self.file.flush()
            if sys.stdout:
                print(line, flush=True)


class Follower(threading.Thread):
    """Copies the service's activity (GET /log: every line the loaders print) into the run's log as it happens."""
    MARKS = {"warning": "!", "error": "x", "debug": "."}

    def __init__(self, log):
        super().__init__(daemon=True)
        self.log = log
        self.after = self.page(10 ** 9).get("last", 0)    # only what happens from now on
        self.done = threading.Event()

    def page(self, after):
        try:
            status, page = call("GET", "%s/log?after=%d&limit=5000" % (BASE, after), 10)
            return page if status == 200 else {}
        except OSError:
            return {}

    def run(self):
        while True:
            last_pass = self.done.is_set()
            page = self.page(self.after)
            for e in page.get("entries", []):
                loader = "[%s] " % e["loader"] if e.get("loader") else ""
                self.log("%s %s%s" % (self.MARKS.get(e.get("level"), " "), loader, e.get("text", "")),
                         clock=(e.get("time") or "")[11:19] or None)
            if "last" in page:
                # A restarted service numbers its lines from 1 again.
                self.after = page["last"] if page["last"] >= self.after or page.get("entries") else 0
            if last_pass:
                return
            self.done.wait(2)

    def finish(self):
        self.done.set()
        self.join(20)


def ensure_service(args, log):
    if healthy():
        log("The SRM loader service is running on %s" % BASE)
        return True
    starter = os.path.join(args.ios_repo, "Services", "mxservices.py")
    if not os.path.isfile(starter):
        log("x %s not found: give --ios-repo, or set MXAPPSIOS_HOME, to the MxAppsIOS checkout" % starter)
        return False
    log("Starting the SRM loader service: mxservices.py start srm-loader")
    try:
        out = subprocess.run([python_exe(), starter, "start", "srm-loader"], cwd=os.path.dirname(starter),
                             capture_output=True, text=True, encoding="utf-8", errors="replace",
                             creationflags=NO_WINDOW, timeout=180)
        for line in (out.stdout + out.stderr).splitlines():
            log("  " + line)
    except (OSError, subprocess.TimeoutExpired) as e:
        log("x could not run mxservices.py: %s" % e)
    return healthy()


def wait_idle(minutes, log):
    """Waits while another refresh or load runs (MxDataHUB, MxJobsMGR, a person); False after `minutes`."""
    deadline, said = time.time() + minutes * 60, False
    while True:
        try:
            status, page = call("GET", BASE + "/log?after=%d&limit=1" % 10 ** 9, 10)
        except OSError:
            return False
        running = page.get("running") if status == 200 else None
        if not running or running.get("dryRun"):
            return True
        if not said:
            log("Waiting: %s is running (%s)" % (running.get("name"), running.get("loader") or "starting"))
            said = True
        if time.time() > deadline:
            log("x still running after %d minutes" % minutes)
            return False
        time.sleep(30)


def choose(categories, args, log):
    """The categories to refresh, in the service's (dependency) order: every daily one and the weekly and monthly
    ones no longer fresh (all with --all), not the optional ones or those in --skip."""
    chosen = []
    for c in categories:
        why = ("--skip" if c["id"] in args.skip
               else "optional" if c.get("optional") and not args.include_optional
               else "fresh, refreshed %s" % c.get("cadence") if not args.all and c.get("cadence") != "daily"
               and c.get("state") == "fresh" else None)
        state = "%s, %s%s" % (c.get("cadence"), c.get("state"), ", data as of %s" % c["dataAsOf"] if c.get("dataAsOf") else "")
        log("  %s %-38s %s" % ("+" if why is None else "-", c.get("name"), state + ("" if why is None else " — not now: " + why)))
        if why is None:
            chosen.append((c["id"], c.get("name") or c["id"]))
    return chosen


def refresh(category, name, args, log):
    headers = {"X-SRM-Dry-Run": "1"} if args.dry_run else {}
    url = "%s/refresh/%s%s" % (BASE, category, "?verbosity=%d" % args.verbosity if args.verbosity else "")
    log("> %s" % name)
    while True:
        try:
            status, body = call("POST", url, args.timeout_hours * 3600, headers)
        except OSError as e:
            status, body = 0, {"error": "the service did not answer: %s" % e}
        if status == 409 and wait_idle(args.wait_minutes, log):
            continue
        break
    if status != 200:
        log("x %s: %s" % (name, body.get("error") or "HTTP %d" % status))
        return {"category": category, "name": name, "status": "failed", "error": body.get("error") or "HTTP %d" % status}
    loaders = ", ".join("%s %s%s" % (l.get("loader"), l.get("status"), " %.0f s" % l["seconds"] if l.get("seconds") else "")
                        for l in body.get("loaders", []))
    mark = {"succeeded": "ok", "dry-run": "ok", "partial": "!"}.get(body.get("status"), "x")
    log("%s %s: %s%s — %s" % (mark, name, body.get("status"),
                              " in %.0f s" % body["seconds"] if body.get("seconds") else "", loaders))
    return {"category": category, "name": name, "status": body.get("status"), "seconds": body.get("seconds"),
            "run": body.get("run"), "loaders": [{k: l.get(k) for k in ("loader", "status", "seconds")}
                                                for l in body.get("loaders", [])]}


def quality(log):
    """The service's data-quality checks (GET /status), as MxJobsMGR's SRM Daily Refresh reads them."""
    try:
        status, body = call("GET", BASE + "/status", 300)
    except OSError as e:
        status, body = 0, {"error": str(e)}
    if status != 200:
        log("! no data-quality checks: %s" % body.get("error"))
        return None
    summary = body.get("summary") or {}
    log("Data-quality checks: %s failed, %s warnings of %s" % (summary.get("failed"), summary.get("warnings"),
                                                              summary.get("checks")))
    for c in body.get("checks", []):
        if c.get("status") != "ok":
            log("  %s %s — %s" % ("x" if c["status"] == "fail" else "!", c.get("name"), c.get("detail")))
    return summary


def run(args):
    log = RunLog(args.log_dir)
    started = dt.datetime.now()
    outcome = {"started": started.isoformat(timespec="seconds"), "dryRun": args.dry_run, "log": log.path,
               "categories": [], "status": "failed"}
    log("MxDataHUB SRM refresh%s on %s, %s" % (" (preview)" if args.dry_run else "", os.environ.get("COMPUTERNAME", ""),
                                              started.strftime("%A %Y-%m-%d %H:%M")))
    try:
        if not ensure_service(args, log):
            log("x The SRM loader service is not running; see `mxservices.py logs srm-loader`")
            return finish(args, log, outcome, 1)
        if not wait_idle(args.wait_minutes, log):
            return finish(args, log, outcome, 1)
        if args.categories:
            plan = [(c, c) for c in args.categories]
        else:
            status, body = call("GET", BASE + "/categories", 120)
            if status != 200:
                log("x The categories could not be read: %s" % (body.get("error") or "HTTP %d" % status).strip())
                if "password" in (body.get("error") or ""):
                    log("  Save the database password: cmdkey /generic:%s /user:postgres /pass" % secrets()[0][0])
                return finish(args, log, outcome, 1)
            log("Categories (+ refreshed now):")
            plan = choose(body.get("categories", []), args, log)
        follower = Follower(log)
        follower.start()
        failed = False
        try:
            for i, (category, name) in enumerate(plan):
                result = refresh(category, name, args, log)
                outcome["categories"].append(result)
                if result["status"] == "failed":
                    failed = True
                    if not args.keep_going:
                        rest = [n for _, n in plan[i + 1:]]
                        if rest:
                            log("x Stopped at the failure, as Refresh All does; not refreshed: %s" % ", ".join(rest))
                        break
        finally:
            follower.finish()
        if not args.dry_run and outcome["categories"]:
            outcome["checks"] = quality(log)
        statuses = [c["status"] for c in outcome["categories"]]
        outcome["status"] = ("failed" if failed else "partial" if "partial" in statuses
                             else "dry-run" if args.dry_run else "succeeded")
        return finish(args, log, outcome, 1 if failed else 0)
    except Exception:
        log("x " + traceback.format_exc().rstrip().replace("\n", "\n            "))
        return finish(args, log, outcome, 1)


def finish(args, log, outcome, code):
    outcome["finished"] = dt.datetime.now().isoformat(timespec="seconds")
    if outcome["status"] == "failed" and not outcome["categories"]:
        outcome["status"] = "error"
    log("Finished: %s (exit %d). Log: %s" % (outcome["status"], code, log.path))
    try:
        with open(os.path.join(args.log_dir, "last-run.json"), "w", encoding="utf-8") as f:
            json.dump(outcome, f, indent=2)
    except OSError:
        pass
    return code


def run_options(args):
    """The run options given to install, as the task passes them to run."""
    out = []
    if args.skip:
        out += ["--skip", ",".join(sorted(args.skip))]
    if args.include_optional:
        out.append("--include-optional")
    if args.keep_going:
        out.append("--keep-going")
    if args.all:
        out.append("--all")
    if args.verbosity:
        out += ["--verbosity", str(args.verbosity)]
    if args.wait_minutes != 120:
        out += ["--wait-minutes", str(args.wait_minutes)]
    return out


def install(args):
    if not WINDOWS:
        sys.exit("install registers a Windows scheduled task")
    m = re.fullmatch(r"([01]?\d|2[0-3]):([0-5]\d)", args.time)
    if not m:
        sys.exit("--time is HH:MM, e.g. 18:30")
    days = parse_days(args.days)
    pythonw = os.path.join(os.path.dirname(python_exe()), "pythonw.exe")
    if not os.path.exists(pythonw):
        sys.exit("pythonw.exe not found beside %s" % python_exe())
    os.makedirs(args.log_dir, exist_ok=True)
    arguments = " ".join(['"%s"' % os.path.abspath(__file__), "run", "--log-dir", '"%s"' % args.log_dir,
                          "--ios-repo", '"%s"' % args.ios_repo] + run_options(args))
    start = "%sT%02d:%s:00" % (dt.date.today().isoformat(), int(m.group(1)), m.group(2))
    xml = TASK_XML % dict(
        description=escape("MxDataHUB's Data Refresh of the security master on this PC (srm.py's loaders through the "
                           "SRM loader service, 127.0.0.1:8770): every daily category and the weekly and monthly ones "
                           "that are due. Logs: %s. Installed by %s." % (args.log_dir, os.path.abspath(__file__))),
        user=escape("%s\\%s" % (os.environ.get("USERDOMAIN", ""), os.environ.get("USERNAME", ""))),
        start=start, days="".join("<%s />" % DAY_NAMES[DAYS.index(d)] for d in days),
        command=escape(pythonw), arguments=escape(arguments), folder=escape(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(args.log_dir, "task.xml")
    with open(path, "w", encoding="utf-16") as f:
        f.write(xml)
    out = subprocess.run(["schtasks", "/Create", "/TN", TASK, "/XML", path, "/F"], capture_output=True, text=True)
    print((out.stdout + out.stderr).strip())
    if out.returncode:
        return 1
    print("\n%s: %s at %s, as %s, while you are signed in.\n" % (TASK, ", ".join(days), args.time,
                                                                   os.environ.get("USERNAME")))
    return status(args)


def parse_days(text):
    text = text.lower().strip()
    if text in ("daily", "all", "every day"):
        return DAYS
    if text in ("mon-fri", "weekdays"):
        return DAYS[:5]
    days = [d.strip()[:3] for d in text.split(",") if d.strip()]
    if not days or any(d not in DAYS for d in days):
        sys.exit("--days is mon-fri, daily, or a list such as mon,wed,fri")
    return [d for d in DAYS if d in days]


def uninstall(args):
    out = subprocess.run(["schtasks", "/Delete", "/TN", TASK, "/F"], capture_output=True, text=True)
    print((out.stdout + out.stderr).strip())
    return out.returncode


def status(args):
    out = subprocess.run(["schtasks", "/Query", "/TN", TASK, "/V", "/FO", "LIST"], capture_output=True, text=True)
    if out.returncode:
        print("The scheduled task %r is not installed: srm_refresh.py install" % TASK)
    else:
        keep = ("TaskName", "Status", "Next Run Time", "Last Run Time", "Last Result", "Logon Mode", "Run As User",
                "Days", "Start Time", "Schedule Type")
        seen = set()
        for line in out.stdout.splitlines():
            key = line.split(":", 1)[0].strip()
            if key in keep and key not in seen:
                seen.add(key)
                print(line)
    print("\nThe SRM loader service: %s" % ("running on " + BASE if healthy() else "not running"))
    try:
        with open(os.path.join(args.log_dir, "last-run.json"), encoding="utf-8") as f:
            last = json.load(f)
        print("Last refresh: %s, %s to %s%s" % (last.get("status"), last.get("started"), last.get("finished"),
                                               " (preview)" if last.get("dryRun") else ""))
        for c in last.get("categories", []):
            print("  %-38s %s" % (c.get("name"), c.get("status")))
        print("  log: %s" % last.get("log"))
    except (OSError, ValueError):
        print("Last refresh: none yet in %s" % args.log_dir)
    try:
        with open(os.path.join(args.ios_repo, "MxDataHUB", "SRM", "config.local.json"), encoding="utf-8") as f:
            configured = bool(json.load(f).get("sec_user_agent"))
    except (OSError, ValueError):
        configured = False
    if os.environ.get("SRM_SEC_USER_AGENT") or configured:
        print("\nSEC User-Agent: set")
    else:
        print("\nSEC User-Agent: not set; SEC EDGAR may refuse the default (it wants a contact e-mail). Add\n"
              '  "sec_user_agent": "Your Name you@example.com"  to %s\n'
              "  (read on every load), or set SRM_SEC_USER_AGENT and restart srm-loader from a new console"
              % os.path.join(args.ios_repo, "MxDataHUB", "SRM", "config.local.json"))
    if WINDOWS:
        print("\nSecrets in the Credential Manager (srm.py also reads environment variables and config.local.json):")
        for target, what in secrets():
            listed = subprocess.run(["cmdkey", "/list:" + target], capture_output=True, text=True).stdout
            saved = "Target:" in listed
            print("  %-5s %-42s %s" % ("saved" if saved else "-", target, what))
            if not saved:
                print("        cmdkey /generic:%s /user:%s /pass" % (target, target.split(":", 1)[1].split("@")[0]))
    return 0


TASK_XML = """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>%(description)s</Description>
  </RegistrationInfo>
  <Triggers>
    <CalendarTrigger>
      <StartBoundary>%(start)s</StartBoundary>
      <Enabled>true</Enabled>
      <ScheduleByWeek>
        <DaysOfWeek>%(days)s</DaysOfWeek>
        <WeeksInterval>1</WeeksInterval>
      </ScheduleByWeek>
    </CalendarTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>%(user)s</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>true</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>true</WakeToRun>
    <ExecutionTimeLimit>PT8H</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>%(command)s</Command>
      <Arguments>%(arguments)s</Arguments>
      <WorkingDirectory>%(folder)s</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""


def main():
    if sys.stdout:
        sys.stdout.reconfigure(errors="replace")
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--log-dir", default=DEFAULT_LOG_DIR)
    common.add_argument("--ios-repo", default=DEFAULT_IOS_REPO)
    options = argparse.ArgumentParser(add_help=False)
    options.add_argument("--all", action="store_true", help="every category, fresh or not (Refresh All)")
    options.add_argument("--skip", type=lambda s: set(filter(None, s.split(","))), default=set())
    options.add_argument("--include-optional", action="store_true")
    options.add_argument("--keep-going", action="store_true", help="go on after a category fails")
    options.add_argument("--verbosity", type=int, choices=(0, 1, 2), default=0)
    options.add_argument("--wait-minutes", type=int, default=120)
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("run", parents=[common, options], help="refresh now")
    p.add_argument("--dry-run", action="store_true", help="only list the commands")
    p.add_argument("--categories", type=lambda s: [c for c in s.split(",") if re.fullmatch(r"[a-z-]+", c)],
                   help="these categories, in this order, whatever their state")
    p.add_argument("--timeout-hours", type=float, default=4)
    p = sub.add_parser("install", parents=[common, options], help="register the scheduled task")
    p.add_argument("--time", default="18:30")
    p.add_argument("--days", default="mon-fri")
    sub.add_parser("uninstall", parents=[common])
    sub.add_parser("status", parents=[common])
    args = parser.parse_args()
    args.ios_repo = os.path.abspath(args.ios_repo)
    args.log_dir = os.path.abspath(args.log_dir)
    sys.exit({"run": run, "install": install, "uninstall": uninstall, "status": status}[args.command](args))


if __name__ == "__main__":
    main()
