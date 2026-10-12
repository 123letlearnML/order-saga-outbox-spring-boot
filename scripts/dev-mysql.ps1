# Chạy 1 instance MySQL RIÊNG cho các dự án, thay cho Docker.
#
# - Dùng lại mysqld.exe đã cài sẵn, nhưng thư mục dữ liệu riêng (D:\mysql-dev\data) và cổng riêng (3307).
# - Không đụng vào MySQL chính (cổng 3306), không cần mật khẩu root của máy.
# - Chỉ nghe trên 127.0.0.1 => máy khác trong mạng không kết nối được.
#
# Cách dùng (PowerShell, từ thư mục gốc của dự án):
#   .\scripts\dev-mysql.ps1
# Lần đầu: khởi tạo thư mục dữ liệu. Mỗi lần chạy: tạo DB/user nếu chưa có (script idempotent).
# Dừng: Ctrl+C trong cửa sổ đang chạy.

param(
    [string]$MysqlHome = 'C:\Program Files\MySQL\MySQL Server 9.4',
    [string]$DataDir = 'D:\mysql-dev\data',
    [int]$Port = 3307
)

$mysqld = Join-Path $MysqlHome 'bin\mysqld.exe'
$mysql = Join-Path $MysqlHome 'bin\mysql.exe'
$setupSql = (Join-Path $PSScriptRoot 'mysql-setup.sql') -replace '\\', '/'

if (-not (Test-Path (Join-Path $DataDir 'mysql'))) {
    Write-Host "Khoi tao data dir $DataDir ..."
    New-Item -ItemType Directory -Force (Split-Path $DataDir) | Out-Null
    # --initialize-insecure: root@localhost không mật khẩu. Chấp nhận được vì chỉ nghe 127.0.0.1 trên máy dev.
    & $mysqld --no-defaults --initialize-insecure "--basedir=$MysqlHome" "--datadir=$DataDir"
}

function Test-Port {
    $tcp = New-Object System.Net.Sockets.TcpClient
    try { $tcp.Connect('127.0.0.1', $Port); return $true } catch { return $false } finally { $tcp.Dispose() }
}

$server = $null
if (Test-Port) {
    Write-Host "MySQL dev da chay san o cong $Port, chi tao database."
} else {
    $serverArgs = @(
        '--no-defaults',
        "--basedir=`"$MysqlHome`"",
        "--datadir=`"$DataDir`"",
        "--port=$Port",
        '--bind-address=127.0.0.1',
        '--mysqlx=OFF',
        '--innodb-buffer-pool-size=512M',
        '--max-connections=300',
        '--console'
    )
    $server = Start-Process -FilePath $mysqld -ArgumentList $serverArgs -PassThru -NoNewWindow
    # Chờ cổng TCP mở. Không gọi mysql.exe ở đây: PowerShell 5.1 coi stderr của exe là lỗi.
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        $ready = Test-Port
        if (-not $ready) { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { Write-Host 'MySQL khong khoi dong duoc, xem file *.err trong data dir'; exit 1 }
}

Write-Host 'Tao database + user devuser (neu chua co) ...'
& $mysql --commands -u root -h 127.0.0.1 -P $Port -e "source $setupSql"

if ($server) {
    Write-Host "MySQL dev dang chay o 127.0.0.1:$Port (Ctrl+C de dung)"
    Wait-Process -Id $server.Id
}
