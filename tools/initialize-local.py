"""Initialize an EMPTY isolated MySQL schema; never imports existing business data.

Requires the mysql CLI, Java 17 and a successfully packaged ruoyi-admin.jar.
The private MySQL option file and LOCAL_ADMIN_PASSWORD stay outside source control.
"""
from pathlib import Path
import argparse
import configparser
import os
import re
import secrets
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-config', required=True, type=Path, help='Private mysql [client] option file, outside this repository')
    parser.add_argument('--jar', type=Path, default=Path(__file__).resolve().parents[1] / 'ruoyi-admin/target/ruoyi-admin.jar')
    args = parser.parse_args()
    backend = Path(__file__).resolve().parents[1]
    option_file = args.mysql_config.resolve(strict=True)
    if option_file.is_relative_to(backend.parent):
        raise ValueError('Keep the private MySQL option file outside the project')
    config = configparser.ConfigParser(interpolation=None)
    config.read(option_file, encoding='utf-8')
    client = config['client']
    if client.get('host') != '127.0.0.1' or client.getint('port') != 13306 or not client.get('user') or client.get('user') == 'root':
        raise ValueError('Only a non-root account at 127.0.0.1:13306 is supported')
    password = os.environ.get('LOCAL_ADMIN_PASSWORD', '')
    if not 16 <= len(password) <= 20 or len(password.encode('utf-8')) > 72:
        raise ValueError('Supply a unique 16–20 character LOCAL_ADMIN_PASSWORD through the environment')
    mysql, java = shutil.which('mysql'), shutil.which('java')
    if not mysql or not java:
        raise ValueError('mysql and Java 17 must be on PATH')
    command = [mysql, '--defaults-extra-file=' + str(option_file), '--protocol=TCP', '--host=127.0.0.1', '--port=13306', '--database=warehouse_local', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']

    def query(sql):
        run = subprocess.run(command, input=sql.encode('utf-8'), capture_output=True)
        if run.returncode:
            raise RuntimeError('Local SQL failed; database remains local. Do not retry against an existing schema. Diagnostics withheld to avoid disclosing SQL credentials.')
        return run.stdout.decode('utf-8').strip()

    identity = query('SELECT @@port,DATABASE(),CURRENT_USER();').split('\t')
    if identity[0:2] != ['13306', 'warehouse_local'] or identity[2].split('@')[0] != client['user']:
        raise ValueError('Database identity does not match the isolated local configuration')
    if query('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') != '0':
        raise ValueError('Refusing to initialize a nonempty database')
    with tempfile.TemporaryDirectory(prefix='warehouse-local-init-') as folder:
        tmp = Path(folder)
        with zipfile.ZipFile(args.jar.resolve(strict=True)) as package:
            libraries = []
            for stem in ['spring-security-crypto-', 'spring-jcl-']:
                entries = [name for name in package.namelist() if name.startswith('BOOT-INF/lib/' + stem) and name.endswith('.jar')]
                if len(entries) != 1:
                    raise ValueError('Build the complete Spring Boot JAR first')
                target = tmp / Path(entries[0]).name
                target.write_bytes(package.read(entries[0])); libraries.append(str(target))
        helper = tmp / 'LocalPasswordHash.java'
        helper.write_text('''import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
class LocalPasswordHash {
 public static void main(String[] args) throws Exception {
  String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
  BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  String hash = encoder.encode(input);
  if (!encoder.matches(input, hash)) throw new IllegalStateException("Password hashing failed");
  System.out.print(hash);
 }
}
''', encoding='utf-8')
        hashed = subprocess.run([java, '-cp', os.pathsep.join(libraries), str(helper)], input=password.encode('utf-8'), capture_output=True)
        if hashed.returncode:
            raise RuntimeError('Password hashing failed; no SQL has been applied')
        digest = hashed.stdout.decode('utf-8')
    if not re.fullmatch(r'\$2a\$12\$[./A-Za-z0-9]{53}', digest):
        raise ValueError('Unexpected BCrypt result; no SQL has been applied')
    files = [backend / 'sql/ry_20260417.sql', backend / 'sql/quartz.sql', *sorted((backend / 'sql').glob('warehouse_[0-9][0-9][0-9]*.sql'))]
    parts = [path.read_text(encoding='utf-8') for path in files]
    if re.search(r'(?im)^\s*(USE\s|CREATE\s+DATABASE|DROP\s+DATABASE)', '\n'.join(parts)):
        raise ValueError('Cross-database SQL is not supported')
    pattern = r"(insert into sys_user values\([12],.*?,\s*')\$2[aby]\$[^']+('.*?;)"
    parts[0], replacements = re.subn(pattern, lambda match: match[1] + digest + match[2], parts[0])
    if replacements != 2:
        raise ValueError('Upstream administrator schema changed; review initialization before use')
    parts.append("UPDATE sys_user SET password='" + digest + "',pwd_update_date=CURRENT_TIMESTAMP,email='',phonenumber='' WHERE user_id IN (1,2);\n"
                 "UPDATE sys_user SET status='1' WHERE user_id=2;\n"
                 "UPDATE sys_config SET config_value='" + secrets.token_urlsafe(15) + "' WHERE config_key='sys.user.initPassword';\n")
    # Recheck immediately before applying; the tool has no drop/reset option.
    if query('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();') != '0':
        raise ValueError('Database became nonempty; initialization cancelled')
    query('SET NAMES utf8mb4;\n' + '\n'.join(parts))
    print('Initialized warehouse_local on 127.0.0.1:13306. Admin password supplied by environment; demo account disabled.')


if __name__ == '__main__':
    main()
