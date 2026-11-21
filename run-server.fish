#!/usr/bin/env fish
# Запуск сервера Slate (fish-версия run-server.sh).
cd (dirname (status filename))

if test -f .env
    for line in (grep -E '^[A-Za-z_][A-Za-z0-9_]*=' .env)
        set -l pair (string split -m1 = -- $line)
        set -gx $pair[1] $pair[2]
    end
end
set -q MONGO_URI; or set -gx MONGO_URI mongodb://localhost:27017
set -q MONGO_DB; or set -gx MONGO_DB canvas_collab
set -q WS_PORT; or set -gx WS_PORT 8765
set -q WS_HOST; or set -gx WS_HOST 127.0.0.1
contains -- --lan $argv; and set -gx WS_HOST 0.0.0.0

function port_open -a port
    bash -c "exec 3<>/dev/tcp/127.0.0.1/$port" 2>/dev/null
end

function pick_java -a need
    for cand in $JAVA_HOME /usr/lib/jvm/openjdk25 /usr/lib/jvm/openjdk21 \
                /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/openjdk17
        test -n "$cand"; and test -x "$cand/bin/java"; or continue
        set -l v (string match -r 'version "([0-9]+)' -- ("$cand/bin/java" -version 2>&1 | head -1))[2]
        test -n "$v"; and test $v -ge $need; and echo "$cand/bin/java"; and return
    end
    command -v java
end

set JAVA_BIN (pick_java 21)
if test -z "$JAVA_BIN"
    echo "Не найден JDK 21+. Установите его или задайте JAVA_HOME." >&2
    exit 1
end

if not port_open 27017
    set -l mongod $HOME/.local/opt/mongodb/bin/mongod
    if test -x $mongod
        echo "MongoDB не запущена — стартую $mongod"
        mkdir -p $HOME/.local/share/canvasproject/mongo
        $mongod --dbpath $HOME/.local/share/canvasproject/mongo \
            --logpath $HOME/.local/share/canvasproject/mongod.log \
            --bind_ip 127.0.0.1 --fork >/dev/null
        for i in (seq 20)
            port_open 27017; and break
            sleep 0.5
        end
    else
        echo "MongoDB не отвечает на 127.0.0.1:27017, а mongod не найден." >&2
        echo "Установите MongoDB или поправьте MONGO_URI в .env." >&2
        exit 1
    end
end

set -l jar java-collab/server/target/canvas-collab-server-1.0.0.jar
if not test -f $jar
    echo "Собираю сервер…"
    ./mvnw -q -f java-collab/server/pom.xml package
end

echo "Сервер: ws://$WS_HOST:$WS_PORT, MongoDB: $MONGO_URI"
exec $JAVA_BIN -jar $jar
