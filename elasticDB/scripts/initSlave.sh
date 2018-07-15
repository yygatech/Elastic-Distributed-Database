SCRIPT_HOME=/home/ubuntu/elasticDB/scripts

echo "SCRIPT_HOME is set to $SCRIPT_HOME"

source $SCRIPT_HOME/set_env.sh

rm -rf /var/lib/mysql
scp -r root@$1:/var/lib/mysql /var/lib 
chown -R mysql.mysql /var/lib/mysql
chmod -R 777 /var/lib/mysql
sed -e "s/server-id=1/server-id=`expr $2`/ig" $SCRIPT_HOME/my.cnf > /etc/mysql/my.cnf
chown -R mysql.mysql /etc/mysql/my.cnf
chmod -R 644 /etc/mysql/my.cnf

if [ $3 == "start" ]; then
  echo "start mysql and also slave"
  /etc/init.d/mysql start
elif [ $3 == "stop" ]; then
  echo "start mysql but not slave"
  rm -rf /var/lib/mysql/*.info
  /etc/init.d/mysql start --skip-slave-start
else
  echo "skip start mysql"
fi
