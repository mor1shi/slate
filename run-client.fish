#!/usr/bin/env fish
# Запуск клиента Slate (fish-версия run-client.sh).
cd (dirname (status filename))

if test -f .env
    for line in (grep -E '^[A-Za-z_][A-Za-z0-9_]*=' .env)
        set -l pair (string split -m1 = -- $line)
        set -gx $pair[1] $pair[2]
    end
end
set -q WS_URL; or set -gx WS_URL ws://localhost:8765

function pick_java_home -a need
    for cand in $JAVA_HOME /usr/lib/jvm/openjdk21 /usr/lib/jvm/java-21-openjdk-amd64 \
                /usr/lib/jvm/openjdk17
        test -n "$cand"; and test -x "$cand/bin/java"; or continue
        set -l v (string match -r 'version "([0-9]+)' -- ("$cand/bin/java" -version 2>&1 | head -1))[2]
        test -n "$v"; and test $v -ge $need; and echo "$cand"; and return
    end
end

set -l home (pick_java_home 17)
if test -z "$home"
    echo "Не найден JDK 17+. Установите его или задайте JAVA_HOME." >&2
    exit 1
end
set -gx JAVA_HOME $home

echo "Клиент: $WS_URL (JDK: $JAVA_HOME)"
exec ./mvnw -q javafx:run
